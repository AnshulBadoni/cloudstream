import { FastifyInstance, FastifyPluginAsync } from 'fastify';
import { redisCache } from '../../redis/clients.js';
import { coalescer } from '../../redis/singleflight.js';
import { tpdbClient } from '../content/tpdb.client.js';
import { discoveryOrchestrator } from '../discovery/orchestrator.service.js';
import { getDb } from '../../db/client.js';
import { content, releases, sources } from '../../db/schema.js';
import { eq, and } from 'drizzle-orm';
import { logger } from '../../utils/logger.js';
import { generateTitleVariants } from '../../utils/title-normalizer.js';

export const streamsRoutes: FastifyPluginAsync = async (fastify: FastifyInstance) => {
  // Movie Stream Discovery Endpoint
  fastify.get<{ Params: { tpdbId: string }; Querystring: { title?: string } }>(
    '/streams/movie/:tpdbId',
    async (request, reply) => {
      let { tpdbId } = request.params;
      let queryTitle = request.query?.title;

      // Handle cases where client passed &title= instead of ?title=
      if (tpdbId.includes('&title=')) {
        const parts = tpdbId.split('&title=');
        tpdbId = parts[0];
        if (!queryTitle) {
          queryTitle = decodeURIComponent(parts[1]);
        }
      }

      const cacheKey = `m:${tpdbId}`;

      const sendSuccess = (streams: any[], fromCache = true) => {
        const normalized = streams.map((s: any) => ({
          ...s,
          url: s.url || s.streamUrl,
          streamUrl: s.streamUrl || s.url,
          source: s.source || s.sourceName || 'TPDB',
          sourceName: s.sourceName || s.source || 'TPDB',
          quality: s.quality || '1080p',
          isM3u8: Boolean(s.isM3u8),
          headers: s.headers || {},
        }));
        return reply.send({
          status: 'SUCCESS',
          content: { id: tpdbId, type: 'MOVIE' },
          results: normalized,
          cached: fromCache,
          lastUpdated: new Date().toISOString(),
        });
      };

      // 1. Check positive cache by ID (Highest Priority)
      const cachedById = await redisCache.get(`str:${cacheKey}`);
      if (cachedById) {
        return sendSuccess(JSON.parse(cachedById), true);
      }

      // 2. If ID misses, check positive cache by Title slug (Priority 2)
      const titleSlug = queryTitle
        ? queryTitle.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')
        : null;

      if (titleSlug) {
        const cachedByTitle = await redisCache.get(`str:t:${titleSlug}`);
        if (cachedByTitle) {
          const parsed = JSON.parse(cachedByTitle);
          // Also link to this ID in Redis so subsequent requests by ID hit immediately
          await redisCache.set(`str:${cacheKey}`, cachedByTitle, 'EX', 86400).catch(() => {});
          return sendSuccess(parsed, true);
        }
      }

      // 3. Check negative cache (bypass if title was provided by client)
      if (!queryTitle) {
        const negCached = await redisCache.get(`neg:${cacheKey}`);
        if (negCached) {
          return reply.send({
            status: 'EMPTY',
            content: { id: tpdbId, type: 'MOVIE' },
            results: [],
            cached: true,
          });
        }
      }

      // 3. Distributed Coalesced Resolution
      const result = await coalescer.executeOrWait(
        cacheKey,
        async () => {
          // Check DB first before triggering scrapers
          try {
            const db = getDb();
            const existingContent = await db.query.content.findFirst({
              where: eq(content.tpdbId, tpdbId),
              with: {
                releases: {
                  with: {
                    sources: true,
                  },
                },
              },
            });

            if (existingContent && existingContent.releases.length > 0) {
              const dbStreams = existingContent.releases.flatMap((r) =>
                r.sources.map((s) => ({
                  sourceId: s.providerId,
                  sourceName: s.providerId,
                  streamUrl: s.streamUrl,
                  quality: r.resolution || '1080p',
                  isM3u8: s.isM3u8,
                  sizeBytes: r.fileSizeBytes || undefined,
                  headers: (s.headers as Record<string, string>) || {},
                }))
              );

              if (dbStreams.length > 0) {
                await redisCache.set(`str:${cacheKey}`, JSON.stringify(dbStreams), 'EX', 86400);
                return { status: 'SUCCESS' as const, streams: dbStreams };
              }
            }
          } catch (dbErr) {
            logger.warn({ err: dbErr, tpdbId }, 'Postgres check failed before discovery; continuing to scrapers');
          }

          // Fetch Canonical Metadata from TPDB & compute intelligent title variants
          const metadata = await tpdbClient.getScene(tpdbId);
          const rawTitle = queryTitle || metadata?.title || `Scene ${tpdbId}`;
          const variants = generateTitleVariants(rawTitle);
          if (metadata?.title) {
            variants.push(...generateTitleVariants(metadata.title));
          }
          if (metadata?.aliases) {
            for (const a of metadata.aliases) {
              variants.push(...generateTitleVariants(a));
            }
          }
          const uniqueAliases = Array.from(new Set(variants));
          const cleanTitle = uniqueAliases.find((v) => v !== rawTitle) || rawTitle;

          // Record process start in Redis (name, id, isProcessing: true)
          const taskInfo = {
            id: tpdbId,
            name: rawTitle,
            isProcessing: true,
            status: 'PROCESSING',
            startedAt: new Date().toISOString(),
          };
          await redisCache.set(`task:m:${tpdbId}`, JSON.stringify(taskInfo), 'EX', 180).catch(() => {});
          await redisCache.hset('active_tasks', tpdbId, JSON.stringify(taskInfo)).catch(() => {});

          try {
            const discResult = await discoveryOrchestrator.discover({
              tpdbId,
              contentType: 'MOVIE',
              title: rawTitle,
              cleanTitle,
              aliases: uniqueAliases,
              releaseDate: metadata?.releaseDate,
            }, 9000);

            // Record process completion in Redis
            const isSuccess = discResult.status === 'SUCCESS' && discResult.streams.length > 0;
            const finishInfo = {
              id: tpdbId,
              name: rawTitle,
              isProcessing: false,
              status: isSuccess ? 'SUCCESS' : 'EMPTY',
              streamCount: discResult.streams.length,
              finishedAt: new Date().toISOString(),
            };
            await redisCache.set(`task:m:${tpdbId}`, JSON.stringify(finishInfo), 'EX', 3600).catch(() => {});
            await redisCache.hdel('active_tasks', tpdbId).catch(() => {});

            return discResult;
          } catch (discErr) {
            await redisCache.set(`task:m:${tpdbId}`, JSON.stringify({
              id: tpdbId,
              name: rawTitle,
              isProcessing: false,
              status: 'FAILED',
              error: (discErr as any)?.message,
              finishedAt: new Date().toISOString(),
            }), 'EX', 3600).catch(() => {});
            await redisCache.hdel('active_tasks', tpdbId).catch(() => {});
            throw discErr;
          }
        },
        9500
      );

      const rawStreams = (result as any).streams || (Array.isArray(result) ? result : []);
      const normalizedStreams = rawStreams.map((s: any) => ({
        sourceId: s.sourceId || 'paradisehill',
        sourceName: s.sourceName || s.source || 'ParadiseHill',
        source: s.source || s.sourceName || 'ParadiseHill',
        streamUrl: s.streamUrl || s.url || '',
        url: s.url || s.streamUrl || '',
        quality: s.quality || '1080p',
        isM3u8: Boolean(s.isM3u8),
        sizeBytes: s.sizeBytes,
        headers: s.headers || {},
      }));

      if (titleSlug && normalizedStreams.length > 0) {
        await redisCache.set(`str:t:${titleSlug}`, JSON.stringify(normalizedStreams), 'EX', 86400).catch(() => {});
      }

      return reply.send({
        status: (result as any).status || (normalizedStreams.length > 0 ? 'SUCCESS' : 'EMPTY'),
        content: { id: tpdbId, type: 'MOVIE' },
        results: normalizedStreams,
        cached: false,
        lastUpdated: new Date().toISOString(),
      });
    }
  );

  // Series / Episode Stream Discovery Endpoint
  fastify.get<{ Params: { tpdbId: string; season: string; episode: string } }>(
    '/streams/series/:tpdbId/:season/:episode',
    async (request, reply) => {
      const { tpdbId, season, episode } = request.params;
      const sNum = parseInt(season, 10);
      const eNum = parseInt(episode, 10);
      const cacheKey = `s:${tpdbId}:${sNum}:${eNum}`;

      // Check positive cache
      const cached = await redisCache.get(`str:${cacheKey}`);
      if (cached) {
        return reply.send({
          status: 'SUCCESS',
          content: { id: tpdbId, type: 'SERIES', season: sNum, episode: eNum },
          results: JSON.parse(cached),
          cached: true,
          lastUpdated: new Date().toISOString(),
        });
      }

      // Check negative cache
      const negCached = await redisCache.get(`neg:${cacheKey}`);
      if (negCached) {
        return reply.send({
          status: 'EMPTY',
          content: { id: tpdbId, type: 'SERIES', season: sNum, episode: eNum },
          results: [],
          cached: true,
        });
      }

      const result = await coalescer.executeOrWait(
        cacheKey,
        async () => {
          return await discoveryOrchestrator.discover({
            tpdbId,
            contentType: 'SERIES',
            title: `Series ${tpdbId}`,
            cleanTitle: `Series ${tpdbId}`,
            aliases: [],
            season: sNum,
            episode: eNum,
          }, 9000);
        },
        9500
      );

      return reply.send({
        status: (result as any).status || 'SUCCESS',
        content: { id: tpdbId, type: 'SERIES', season: sNum, episode: eNum },
        results: (result as any).streams || (Array.isArray(result) ? result : []),
        cached: false,
        lastUpdated: new Date().toISOString(),
      });
    }
  );
};
