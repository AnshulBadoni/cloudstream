import { adapterRegistry } from './adapters/registry.js';
import { DiscoveredStream, SearchQuery } from './adapters/base.adapter.js';
import { circuitBreaker } from '../../utils/circuit-breaker.js';
import { env } from '../../config/env.js';
import { logger } from '../../utils/logger.js';
import { getDb } from '../../db/client.js';
import { content, releases, sources } from '../../db/schema.js';
import { appendToDbWal } from './wal-sync.service.js';
import { redisCache } from '../../redis/clients.js';
import { discoveryQueue } from '../../queue/discovery.queue.js';

export interface DiscoveryResult {
  status: 'SUCCESS' | 'EMPTY' | 'INDEXING' | 'FAILED';
  streams: DiscoveredStream[];
  fromCache?: boolean;
}

export class DiscoveryOrchestrator {
  /**
   * Run synchronous discovery across enabled adapters within timeout budget.
   */
  async discover(query: SearchQuery, timeoutMs: number = env.SYNC_DISCOVERY_TIMEOUT_MS): Promise<DiscoveryResult> {
    const adapters = adapterRegistry.getEnabled().filter((a) => !circuitBreaker.isOpen(a.id));

    if (adapters.length === 0) {
      logger.warn({ query }, 'No active adapters available for discovery');
      return { status: 'EMPTY', streams: [] };
    }

    const discoveryStartTime = Date.now();

    const searchPromises = adapters.map(async (adapter) => {
      try {
        const results = await adapter.search(query, timeoutMs);
        circuitBreaker.recordSuccess(adapter.id);
        return results;
      } catch (err) {
        circuitBreaker.recordFailure(adapter.id);
        logger.error({ adapter: adapter.id, err }, 'Adapter search threw exception');
        return [] as DiscoveredStream[];
      }
    });

    const settled = await Promise.allSettled(searchPromises);
    const elapsed = Date.now() - discoveryStartTime;

    const allStreams = settled.flatMap((s) => (s.status === 'fulfilled' ? s.value : []));
    const dedupedStreams = this.deduplicateAndRank(allStreams);

    // If discovery exceeded timeout budget and returned no streams, dispatch to background queue
    if (elapsed >= timeoutMs && dedupedStreams.length === 0) {
      logger.info({ tpdbId: query.tpdbId, elapsed }, 'Sync discovery deadline hit with 0 streams. Enqueuing background task.');
      await discoveryQueue.add(
        'deep_discovery',
        { query },
        { jobId: `disc:${query.tpdbId}`, attempts: 3 }
      ).catch(() => {});

      return { status: 'INDEXING', streams: [] };
    }

    if (dedupedStreams.length === 0) {
      // Negative cache for 2 hours
      await redisCache.set(`neg:m:${query.tpdbId}`, JSON.stringify({ status: 'EMPTY' }), 'EX', 7200);
      return { status: 'EMPTY', streams: [] };
    }

    // Persist discovered streams
    await this.persistResults(query, dedupedStreams);

    // Cache active streams for 24 hours (86,400s)
    await redisCache.set(`str:m:${query.tpdbId}`, JSON.stringify(dedupedStreams), 'EX', 86400);

    return { status: 'SUCCESS', streams: dedupedStreams };
  }

  private deduplicateAndRank(streams: DiscoveredStream[]): DiscoveredStream[] {
    const seen = new Set<string>();
    const unique: DiscoveredStream[] = [];

    // Sort by quality and confidence
    const qualityScore = (q: string) => {
      switch (q.toLowerCase()) {
        case '2160p':
        case '4k':
          return 4;
        case '1080p':
          return 3;
        case '720p':
          return 2;
        case '480p':
          return 1;
        default:
          return 0;
      }
    };

    const sorted = [...streams].sort((a, b) => {
      const qDiff = qualityScore(b.quality) - qualityScore(a.quality);
      if (qDiff !== 0) return qDiff;
      return b.confidenceScore - a.confidenceScore;
    });

    for (const s of sorted) {
      const key = `${s.quality}:${s.streamUrl}`;
      if (!seen.has(key)) {
        seen.add(key);
        unique.push(s);
      }
    }

    return unique;
  }

  private async persistResults(query: SearchQuery, streams: DiscoveredStream[]): Promise<void> {
    const contentPayload = {
      tpdbId: query.tpdbId,
      contentType: query.contentType,
      title: query.title,
      releaseDate: query.releaseDate,
    };

    const releasePayloads = streams.map((s) => ({
      releaseTitle: `${query.title} [${s.sourceName}] ${s.quality}`,
      resolution: s.quality,
      seasonNumber: query.season,
      episodeNumber: query.episode,
      fileSizeBytes: s.sizeBytes,
    }));

    const sourcePayloads = streams.map((s) => ({
      providerId: s.sourceId,
      streamUrl: s.streamUrl,
      isDirectStream: !s.isM3u8,
      isM3u8: s.isM3u8,
      headers: s.headers || {},
      status: 'ACTIVE',
    }));

    try {
      const db = getDb();
      await db.transaction(async (tx) => {
        const [c] = await tx
          .insert(content)
          .values(contentPayload)
          .onConflictDoUpdate({
            target: content.tpdbId,
            set: { updatedAt: new Date(), title: contentPayload.title },
          })
          .returning();

        for (let i = 0; i < streams.length; i++) {
          const r = releasePayloads[i];
          const s = sourcePayloads[i];

          const [savedRelease] = await tx
            .insert(releases)
            .values({ ...r, contentId: c.id })
            .returning();

          await tx.insert(sources).values({ ...s, releaseId: savedRelease.id });
        }
      });
    } catch (dbErr) {
      logger.error({ err: dbErr, tpdbId: query.tpdbId }, 'Postgres write failed. Spilling over to durable Redis Stream WAL.');
      await appendToDbWal({
        content: contentPayload as any,
        releases: releasePayloads as any,
        sources: sourcePayloads as any,
      });
    }
  }
}

export const discoveryOrchestrator = new DiscoveryOrchestrator();
