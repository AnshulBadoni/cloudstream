import Fastify from 'fastify';
import cors from '@fastify/cors';
import rateLimit from '@fastify/rate-limit';
import { env } from './config/env.js';
import { logger } from './utils/logger.js';
import { initRedis, closeRedis, redisCache } from './redis/clients.js';
import { closeDb, getDb } from './db/client.js';
import { content, releases, sources } from './db/schema.js';
import { streamsRoutes } from './modules/streams/streams.routes.js';
import { adminRoutes } from './modules/admin/admin.routes.js';
import { register, httpRequestDurationMicroseconds } from './metrics/metrics.js';
import { startWorker, stopWorker } from './queue/discovery.worker.js';
import { discoveryQueue } from './queue/discovery.queue.js';
import { SpeedPornAdapter } from './modules/discovery/adapters/speedporn.adapter.js';
import { ParadiseHillAdapter } from './modules/discovery/adapters/paradisehill.adapter.js';
import { PornoTorrentAdapter } from './modules/discovery/adapters/pornotorrent.adapter.js';
import { FilmAdultAdapter } from './modules/discovery/adapters/filmadult.adapter.js';
import { circuitBreaker } from './utils/circuit-breaker.js';

export async function buildApp() {
  const fastify = Fastify({
    logger: false, // Pino handled manually
  });

  await fastify.register(cors, {
    origin: '*',
  });

  await fastify.register(rateLimit, {
    max: 120,
    timeWindow: '1 minute',
  });

  // Performance metrics hook
  fastify.addHook('onRequest', async (req) => {
    (req.raw as any).startTime = process.hrtime();
  });

  fastify.addHook('onResponse', async (req, reply) => {
    const startTime = (req.raw as any).startTime;
    if (startTime) {
      const diff = process.hrtime(startTime);
      const durationSeconds = diff[0] + diff[1] / 1e9;
      httpRequestDurationMicroseconds
        .labels(req.method, req.routeOptions.url || req.url, reply.statusCode.toString())
        .observe(durationSeconds);
    }
  });

  // Health check endpoint
  fastify.get('/api/v1/health', async () => {
    return { status: 'healthy', timestamp: new Date().toISOString() };
  });

  // Diagnostic Scraping endpoint
  fastify.get<{ Querystring: { title?: string; term?: string } }>('/api/v1/diag', async (req) => {
    const term = req.query?.term || req.query?.title || 'Tushy Raw 68';
    const query = {
      tpdbId: 'diag_test',
      contentType: 'MOVIE' as const,
      title: term,
      cleanTitle: term,
      aliases: [term],
    };

    const sp = new SpeedPornAdapter();
    const ph = new ParadiseHillAdapter();
    const pt = new PornoTorrentAdapter();
    const fa = new FilmAdultAdapter();

    const spStart = Date.now();
    let spResults: any = [];
    let spError: any = null;
    try {
      spResults = await sp.search(query, 12000);
    } catch (e: any) {
      spError = { message: e.message, name: e.name, stack: e.stack };
    }
    const spDuration = Date.now() - spStart;

    const phStart = Date.now();
    let phResults: any = [];
    let phError: any = null;
    try {
      phResults = await ph.search(query, 12000);
    } catch (e: any) {
      phError = { message: e.message, name: e.name, stack: e.stack };
    }
    const phDuration = Date.now() - phStart;

    const ptStart = Date.now();
    let ptResults: any = [];
    let ptError: any = null;
    try {
      ptResults = await pt.search(query, 12000);
    } catch (e: any) {
      ptError = { message: e.message, name: e.name, stack: e.stack };
    }
    const ptDuration = Date.now() - ptStart;

    const faStart = Date.now();
    let faResults: any = [];
    let faError: any = null;
    try {
      faResults = await fa.search(query, 12000);
    } catch (e: any) {
      faError = { message: e.message, name: e.name, stack: e.stack };
    }
    const faDuration = Date.now() - faStart;

    let spDirectProbe: any = {};
    try {
      const probeUrl = `https://speedporn.net/${term.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')}/`;
      const pRes = await fetch(probeUrl, {
        headers: {
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
          'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
        },
      });
      const pHtml = await pRes.text();
      spDirectProbe = {
        probeUrl,
        status: pRes.status,
        htmlLength: pHtml.length,
        hasDataServers: pHtml.includes('data-servers='),
        snippet: pHtml.slice(0, 300),
      };
    } catch (e: any) {
      spDirectProbe = { error: e.message };
    }

    return {
      term,
      speedporn: { durationMs: spDuration, streamCount: spResults.length, streams: spResults, error: spError, directProbe: spDirectProbe },
      paradisehill: { durationMs: phDuration, streamCount: phResults.length, streams: phResults, error: phError },
      pornotorrent: { durationMs: ptDuration, streamCount: ptResults.length, streams: ptResults, error: ptError },
      filmadult: { durationMs: faDuration, streamCount: faResults.length, streams: faResults, error: faError },
      circuitBreaker: {
        speedporn: circuitBreaker.isOpen('speedporn'),
        paradisehill: circuitBreaker.isOpen('paradisehill'),
        pornotorrent: circuitBreaker.isOpen('pornotorrent'),
        filmadult: circuitBreaker.isOpen('filmadult'),
      },
    };
  });

  // Prometheus Metrics endpoint
  fastify.get('/metrics', async (req, reply) => {
    reply.header('Content-Type', register.contentType);
    return reply.send(await register.metrics());
  });

  // Task Monitoring JSON API
  fastify.get('/api/v1/admin/tasks', async () => {
    const activeMap = await redisCache.hgetall('active_tasks').catch(() => ({}));
    const activeTasks = Object.values(activeMap)
      .map((val) => {
        try {
          return JSON.parse(val);
        } catch {
          return null;
        }
      })
      .filter(Boolean);

    const keys = await redisCache.keys('task:m:*').catch(() => []);
    const recentTasks: any[] = [];
    if (keys.length > 0) {
      const values = await redisCache.mget(...keys).catch(() => []);
      for (const v of values) {
        if (!v) continue;
        try {
          const parsed = JSON.parse(v);
          if (!parsed.isProcessing) {
            recentTasks.push(parsed);
          }
        } catch {}
      }
    }
    recentTasks.sort(
      (a, b) => new Date(b.finishedAt || 0).getTime() - new Date(a.finishedAt || 0).getTime()
    );

    const jobCounts = await discoveryQueue
      .getJobCounts('waiting', 'active', 'completed', 'failed')
      .catch(() => ({
        waiting: 0,
        active: 0,
        completed: 0,
        failed: 0,
      }));

    return {
      activeTasks,
      recentTasks: recentTasks.slice(0, 50),
      queue: jobCounts,
      timestamp: new Date().toISOString(),
    };
  });

  // Helper: Strip emojis and irregular symbols
  function cleanTextWithoutEmojis(str: string): string {
    if (!str) return '';
    return str
      .replace(/[\u{1F600}-\u{1F64F}\u{1F300}-\u{1F5FF}\u{1F680}-\u{1F6FF}\u{1F1E0}-\u{1F1FF}\u{2600}-\u{26FF}\u{2700}-\u{27BF}\u{1F900}-\u{1F9FF}\u{1FA70}-\u{1FAFF}\u{FE00}-\u{FE0F}]/gu, '')
      .replace(/[^\x20-\x7E\u00A0-\u024F]/g, '')
      .replace(/\s+/g, ' ')
      .trim();
  }

  // Video Library API: Paged retrieval from PostgreSQL & Redis with emoji stripping & full links
  fastify.get<{
    Querystring: {
      page?: string;
      limit?: string;
      source?: 'all' | 'db' | 'redis';
      search?: string;
    };
  }>('/api/v1/admin/videos', async (req) => {
    const rawPage = parseInt(req.query.page || '1', 10);
    const page = isNaN(rawPage) || rawPage < 1 ? 1 : rawPage;
    const rawLimit = parseInt(req.query.limit || '10', 10);
    const limit = Math.min(100, Math.max(10, isNaN(rawLimit) ? 10 : rawLimit));
    const filterSource = req.query.source || 'all';
    const searchTerm = (req.query.search || '').toLowerCase().trim();

    const itemMap = new Map<string, {
      id: string;
      title: string;
      storage: 'DB' | 'Redis' | 'DB + Redis';
      createdAt: string;
      streams: Array<{
        source: string;
        url: string;
        quality: string;
        isM3u8: boolean;
        headers?: Record<string, string>;
      }>;
    }>();

    // 1. Fetch from PostgreSQL
    try {
      const db = getDb();
      const dbRows = await db.query.content.findMany({
        with: {
          releases: {
            with: {
              sources: true,
            },
          },
        },
      });

      for (const c of dbRows) {
        const cleanTitle = cleanTextWithoutEmojis(c.title || `Item ${c.tpdbId}`);
        const streamList: any[] = [];
        for (const r of c.releases || []) {
          for (const s of r.sources || []) {
            streamList.push({
              source: s.providerId || 'Database',
              url: s.streamUrl,
              quality: r.resolution || '1080p',
              isM3u8: Boolean(s.isM3u8),
              headers: (s.headers as Record<string, string>) || {},
            });
          }
        }

        itemMap.set(c.tpdbId, {
          id: c.tpdbId,
          title: cleanTitle,
          storage: 'DB',
          createdAt: c.createdAt ? new Date(c.createdAt).toISOString() : new Date().toISOString(),
          streams: streamList,
        });
      }
    } catch (err: any) {
      logger.warn({ err: err.message }, 'Failed reading videos from PostgreSQL, falling back to Redis');
    }

    // 2. Fetch from Redis (Cache)
    try {
      const strMKeys = await redisCache.keys('str:m:*').catch(() => []);
      const strTKeys = await redisCache.keys('str:t:*').catch(() => []);
      const allKeys = Array.from(new Set([...strMKeys, ...strTKeys]));

      if (allKeys.length > 0) {
        const values = await redisCache.mget(...allKeys).catch(() => []);
        for (let i = 0; i < allKeys.length; i++) {
          const key = allKeys[i];
          const val = values[i];
          if (!val) continue;

          let parsedStreams: any[] = [];
          try {
            parsedStreams = JSON.parse(val);
          } catch {
            continue;
          }

          if (!Array.isArray(parsedStreams) || parsedStreams.length === 0) continue;

          const isSlugKey = key.startsWith('str:t:');
          const keyIdentifier = isSlugKey ? key.substring(6) : key.substring(6);

          const normalizedList = parsedStreams.map((s: any) => ({
            source: s.source || s.sourceName || s.sourceId || 'Redis',
            url: s.url || s.streamUrl || '',
            quality: s.quality || '1080p',
            isM3u8: Boolean(s.isM3u8) || (s.url || s.streamUrl || '').includes('.m3u8'),
            headers: s.headers || {},
          }));

          if (itemMap.has(keyIdentifier)) {
            const existing = itemMap.get(keyIdentifier)!;
            existing.storage = 'DB + Redis';
            const existingUrls = new Set(existing.streams.map((s) => s.url));
            for (const ns of normalizedList) {
              if (ns.url && !existingUrls.has(ns.url)) {
                existing.streams.push(ns);
                existingUrls.add(ns.url);
              }
            }
          } else {
            let title = keyIdentifier;
            if (isSlugKey) {
              title = keyIdentifier
                .split('-')
                .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
                .join(' ');
            }
            title = cleanTextWithoutEmojis(title);

            itemMap.set(keyIdentifier, {
              id: keyIdentifier,
              title,
              storage: 'Redis',
              createdAt: new Date().toISOString(),
              streams: normalizedList,
            });
          }
        }
      }
    } catch (err: any) {
      logger.warn({ err: err.message }, 'Failed reading videos from Redis');
    }

    let allVideos = Array.from(itemMap.values());

    // 3. Filter by Storage
    if (filterSource === 'db') {
      allVideos = allVideos.filter((v) => v.storage === 'DB' || v.storage === 'DB + Redis');
    } else if (filterSource === 'redis') {
      allVideos = allVideos.filter((v) => v.storage === 'Redis' || v.storage === 'DB + Redis');
    }

    // 4. Filter by Search Query
    if (searchTerm) {
      allVideos = allVideos.filter(
        (v) =>
          v.title.toLowerCase().includes(searchTerm) ||
          v.id.toLowerCase().includes(searchTerm) ||
          v.streams.some((s) => s.url.toLowerCase().includes(searchTerm) || s.source.toLowerCase().includes(searchTerm))
      );
    }

    // 5. Sort: Most streams first, then title
    allVideos.sort((a, b) => b.streams.length - a.streams.length);

    // 6. Pagination (10 to 100 per page)
    const total = allVideos.length;
    const totalPages = Math.ceil(total / limit) || 1;
    const safePage = Math.min(page, totalPages);
    const startIndex = (safePage - 1) * limit;
    const pagedItems = allVideos.slice(startIndex, startIndex + limit);

    return {
      total,
      page: safePage,
      limit,
      totalPages,
      items: pagedItems,
      timestamp: new Date().toISOString(),
    };
  });

  // Manual Stream Insertion API: Permanently save stream into PostgreSQL & Redis
  fastify.post<{
    Body: {
      title: string;
      tpdbId?: string;
      streamUrl: string;
      sourceName?: string;
      quality?: string;
      isM3u8?: boolean;
      headers?: Record<string, string>;
    };
  }>('/api/v1/admin/videos', async (req, reply) => {
    const { title, streamUrl, tpdbId, sourceName = 'Manual', quality = '1080p', isM3u8 = false, headers = {} } = req.body || {};
    if (!title || !streamUrl) {
      return reply.status(400).send({ error: 'title and streamUrl are required' });
    }

    const cleanTitle = cleanTextWithoutEmojis(title);
    const cleanId = (tpdbId || cleanTitle.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')).trim();
    const titleSlug = cleanTitle.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
    const detectedM3u8 = Boolean(isM3u8) || streamUrl.includes('.m3u8');

    const streamObj = {
      sourceId: sourceName.toLowerCase().replace(/\s+/g, '-'),
      sourceName,
      source: sourceName,
      streamUrl,
      url: streamUrl,
      quality,
      isM3u8: detectedM3u8,
      headers,
      confidenceScore: 1.0,
    };

    // 1. Save to PostgreSQL
    try {
      const db = getDb();
      await db.transaction(async (tx) => {
        const [c] = await tx
          .insert(content)
          .values({
            tpdbId: cleanId,
            contentType: 'MOVIE',
            title: cleanTitle,
          })
          .onConflictDoUpdate({
            target: content.tpdbId,
            set: { updatedAt: new Date(), title: cleanTitle },
          })
          .returning();

        const [rel] = await tx
          .insert(releases)
          .values({
            contentId: c.id,
            releaseTitle: `${cleanTitle} [${sourceName}] ${quality}`,
            resolution: quality,
          })
          .returning();

        await tx.insert(sources).values({
          releaseId: rel.id,
          providerId: sourceName,
          streamUrl,
          isDirectStream: !detectedM3u8,
          isM3u8: detectedM3u8,
          headers,
          status: 'ACTIVE',
        });
      });
    } catch (err: any) {
      logger.warn({ err: err.message }, 'Failed persisting manual stream to PostgreSQL, proceeding with Redis');
    }

    // 2. Save / Update in Redis cache (both ID and Title Slug)
    try {
      const existingStr = await redisCache.get(`str:m:${cleanId}`).catch(() => null);
      let currentStreams: any[] = [];
      if (existingStr) {
        try {
          currentStreams = JSON.parse(existingStr);
        } catch {}
      }
      if (!Array.isArray(currentStreams)) currentStreams = [];

      if (!currentStreams.some((s: any) => (s.url || s.streamUrl) === streamUrl)) {
        currentStreams.push(streamObj);
      }

      await redisCache.set(`str:m:${cleanId}`, JSON.stringify(currentStreams), 'EX', 86400 * 7);
      if (titleSlug) {
        await redisCache.set(`str:t:${titleSlug}`, JSON.stringify(currentStreams), 'EX', 86400 * 7);
      }
    } catch (err: any) {
      logger.warn({ err: err.message }, 'Failed writing manual stream to Redis');
    }

    return reply.send({
      status: 'SUCCESS',
      message: 'Stream saved permanently to Database & Redis',
      video: {
        id: cleanId,
        title: cleanTitle,
        stream: streamObj,
      },
    });
  });

  // Web Dashboard for Monitoring Scraping & Queue Status
  fastify.get('/dashboard', async (req, reply) => {
    const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>CloudStream Discovery & Library Monitor</title>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;600&display=swap" rel="stylesheet">
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      background: #090d16;
      color: #e2e8f0;
      font-family: 'Inter', sans-serif;
      min-height: 100vh;
      padding: 24px;
    }
    .container { max-width: 1280px; margin: 0 auto; }
    header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 24px;
      padding-bottom: 16px;
      border-bottom: 1px solid #1e293b;
    }
    .logo-area { display: flex; align-items: center; gap: 12px; }
    .pulse-dot {
      width: 12px;
      height: 12px;
      background: #10b981;
      border-radius: 50%;
      box-shadow: 0 0 12px #10b981;
      animation: pulse 2s infinite;
    }
    @keyframes pulse {
      0% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.7); }
      70% { transform: scale(1); box-shadow: 0 0 0 10px rgba(16, 185, 129, 0); }
      100% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(16, 185, 129, 0); }
    }
    h1 { font-size: 1.5rem; font-weight: 700; color: #f8fafc; }
    .status-badge {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      background: rgba(16, 185, 129, 0.15);
      border: 1px solid rgba(16, 185, 129, 0.3);
      color: #34d399;
      padding: 4px 10px;
      border-radius: 9999px;
      font-size: 0.8rem;
      font-weight: 600;
    }
    .metrics-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
      gap: 16px;
      margin-bottom: 24px;
    }
    .metric-card {
      background: #131b2e;
      border: 1px solid #1e293b;
      border-radius: 12px;
      padding: 16px 20px;
      position: relative;
      overflow: hidden;
    }
    .metric-card::before {
      content: '';
      position: absolute;
      top: 0; left: 0; right: 0; height: 3px;
      background: linear-gradient(90deg, #38bdf8, #818cf8);
    }
    .metric-title { font-size: 0.82rem; color: #94a3b8; margin-bottom: 6px; text-transform: uppercase; letter-spacing: 0.05em; }
    .metric-value { font-size: 1.85rem; font-weight: 700; color: #f1f5f9; font-family: 'JetBrains Mono', monospace; }
    .metric-desc { font-size: 0.75rem; color: #64748b; margin-top: 4px; }
    .card {
      background: #131b2e;
      border: 1px solid #1e293b;
      border-radius: 12px;
      padding: 20px;
      margin-bottom: 24px;
    }
    .card-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 16px;
    }
    .card-title { font-size: 1.15rem; font-weight: 600; color: #f8fafc; display: flex; align-items: center; gap: 8px; }
    table { width: 100%; border-collapse: collapse; font-size: 0.9rem; }
    th {
      text-align: left;
      padding: 10px 14px;
      color: #94a3b8;
      font-weight: 600;
      border-bottom: 1px solid #243048;
      font-size: 0.75rem;
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }
    td {
      padding: 12px 14px;
      border-bottom: 1px solid #192339;
      color: #cbd5e1;
      vertical-align: top;
    }
    tr:last-child td { border-bottom: none; }
    .badge {
      display: inline-flex;
      align-items: center;
      padding: 3px 8px;
      border-radius: 6px;
      font-size: 0.75rem;
      font-weight: 600;
      font-family: 'JetBrains Mono', monospace;
    }
    .badge-processing { background: rgba(56, 189, 248, 0.2); color: #38bdf8; border: 1px solid rgba(56, 189, 248, 0.4); animation: pulse 1.5s infinite; }
    .badge-success { background: rgba(34, 197, 94, 0.2); color: #4ade80; border: 1px solid rgba(34, 197, 94, 0.4); }
    .badge-empty { background: rgba(148, 163, 184, 0.15); color: #94a3b8; border: 1px solid rgba(148, 163, 184, 0.3); }
    .badge-failed { background: rgba(239, 68, 68, 0.2); color: #f87171; border: 1px solid rgba(239, 68, 68, 0.4); }
    .badge-db { background: rgba(16, 185, 129, 0.2); color: #34d399; border: 1px solid rgba(16, 185, 129, 0.4); }
    .badge-redis { background: rgba(245, 158, 11, 0.2); color: #fbbf24; border: 1px solid rgba(245, 158, 11, 0.4); }
    .badge-hybrid { background: rgba(168, 85, 247, 0.2); color: #c084fc; border: 1px solid rgba(168, 85, 247, 0.4); }
    .badge-qual { background: #1e293b; color: #38bdf8; border: 1px solid #334155; }
    .mono { font-family: 'JetBrains Mono', monospace; font-size: 0.8rem; }
    .empty-msg { text-align: center; padding: 28px; color: #64748b; font-style: italic; }
    .refresh-bar {
      display: flex;
      align-items: center;
      gap: 12px;
      font-size: 0.85rem;
      color: #94a3b8;
    }
    button, .btn {
      background: #2563eb;
      color: #fff;
      border: none;
      padding: 7px 14px;
      border-radius: 6px;
      font-weight: 600;
      cursor: pointer;
      font-size: 0.85rem;
      text-decoration: none;
      display: inline-flex;
      align-items: center;
      gap: 6px;
    }
    button:hover, .btn:hover { background: #1d4ed8; }
    .btn-secondary { background: #1e293b; color: #94a3b8; border: 1px solid #334155; }
    .btn-secondary:hover { background: #334155; color: #fff; }
    .btn-sm { padding: 4px 8px; font-size: 0.75rem; }
    .input-row { display: flex; gap: 8px; margin-top: 12px; }
    input[type="text"], select {
      background: #090d16;
      border: 1px solid #243048;
      border-radius: 6px;
      padding: 8px 12px;
      color: #f1f5f9;
      font-size: 0.88rem;
    }
    input[type="text"]:focus, select:focus { outline: none; border-color: #38bdf8; }
    .controls-bar {
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 16px;
      padding-bottom: 14px;
      border-bottom: 1px solid #1a2438;
    }
    .controls-left, .controls-right { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
    .stream-drawer {
      background: #0a0f1d;
      border: 1px solid #1e293b;
      border-radius: 8px;
      padding: 12px;
      margin-top: 10px;
    }
    .stream-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      padding: 8px 0;
      border-bottom: 1px solid #162035;
      font-size: 0.82rem;
    }
    .stream-row:last-child { border-bottom: none; }
    .stream-left { display: flex; align-items: center; gap: 8px; flex: 1; min-width: 0; }
    .stream-url-text {
      font-family: 'JetBrains Mono', monospace;
      color: #38bdf8;
      text-decoration: none;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      max-width: 620px;
      display: inline-block;
    }
    .stream-url-text:hover { text-decoration: underline; }
    .pagination-bar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-top: 18px;
      padding-top: 14px;
      border-top: 1px solid #1a2438;
      font-size: 0.85rem;
      color: #94a3b8;
    }
    .manual-form-card {
      background: #0d1424;
      border: 1px dashed #334155;
      border-radius: 8px;
      padding: 16px;
      margin-bottom: 18px;
      display: none;
    }
    .form-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
      gap: 12px;
      margin-bottom: 12px;
    }
    .form-group { display: flex; flex-direction: column; gap: 4px; }
    .form-group label { font-size: 0.75rem; text-transform: uppercase; color: #94a3b8; font-weight: 600; letter-spacing: 0.04em; }
  </style>
</head>
<body>
  <div class="container">
    <header>
      <div class="logo-area">
        <div class="pulse-dot"></div>
        <h1>CloudStream Task & Library Monitor</h1>
        <span class="status-badge">System Live</span>
      </div>
      <div class="refresh-bar">
        <span>Auto-refreshing in <b id="countdown" class="mono">3</b>s</span>
        <button onclick="fetchTasks(); fetchLibrary();">Refresh All</button>
      </div>
    </header>

    <div class="metrics-grid">
      <div class="metric-card">
        <div class="metric-title">Active In-Flight</div>
        <div class="metric-value" id="val-active" style="color: #38bdf8;">0</div>
        <div class="metric-desc">Live scraping tasks in progress</div>
      </div>
      <div class="metric-card">
        <div class="metric-title">Indexed in DB & Redis</div>
        <div class="metric-value" id="val-library" style="color: #c084fc;">0</div>
        <div class="metric-desc">Permanent videos with stored links</div>
      </div>
      <div class="metric-card">
        <div class="metric-title">Recent Scrapes</div>
        <div class="metric-value" id="val-recent" style="color: #4ade80;">0</div>
        <div class="metric-desc">Cached results in past hour</div>
      </div>
      <div class="metric-card">
        <div class="metric-title">Queue Waiting</div>
        <div class="metric-value" id="val-waiting">0</div>
        <div class="metric-desc">Jobs queued in BullMQ</div>
      </div>
      <div class="metric-card">
        <div class="metric-title">Queue Failures</div>
        <div class="metric-value" id="val-failed" style="color: #f87171;">0</div>
        <div class="metric-desc">Queue job error count</div>
      </div>
    </div>

    <!-- Active In-Flight Tasks -->
    <div class="card">
      <div class="card-header">
        <div class="card-title">
          <span>[ACTIVE] Scrapes Currently In-Flight</span>
          <span id="active-count-pill" class="badge badge-processing">0 Active</span>
        </div>
      </div>
      <div id="active-tasks-container">
        <div class="empty-msg">No active scrapes running right now. Ready for incoming requests.</div>
      </div>
    </div>

    <!-- Video Library (PostgreSQL & Redis) -->
    <div class="card">
      <div class="card-header">
        <div>
          <div class="card-title">[LIBRARY] Stored Video Database & Redis Cache</div>
          <div style="font-size: 0.8rem; color: #94a3b8; margin-top: 4px;">Live inventory of all stored titles with full streaming links, direct MP4/M3U8/Torrent indicators, and pagination.</div>
        </div>
        <button class="btn-secondary" onclick="toggleAddForm()">+ Add Custom Link</button>
      </div>

      <!-- Add Video Form -->
      <div id="add-video-panel" class="manual-form-card">
        <div style="font-size: 0.9rem; font-weight: 600; color: #f8fafc; margin-bottom: 12px;">Save Video Stream Permanently to DB & Redis</div>
        <div class="form-grid">
          <div class="form-group">
            <label>Title (Required)</label>
            <input type="text" id="m-title" placeholder="e.g. Up Close 4" />
          </div>
          <div class="form-group">
            <label>TPDB ID / Slug (Optional)</label>
            <input type="text" id="m-id" placeholder="e.g. up-close-4 or numeric ID" />
          </div>
          <div class="form-group">
            <label>Quality</label>
            <input type="text" id="m-quality" value="1080p" placeholder="1080p, 720p, 4k" />
          </div>
          <div class="form-group">
            <label>Source Provider</label>
            <input type="text" id="m-source" value="Manual" placeholder="Manual, Personal, Archive" />
          </div>
        </div>
        <div class="form-group" style="margin-bottom: 14px;">
          <label>Stream URL or Magnet (Required)</label>
          <input type="text" id="m-url" placeholder="https://.../video.mp4 or https://.../playlist.m3u8 or magnet:?xt=urn:..." />
        </div>
        <div style="display: flex; gap: 10px; align-items: center;">
          <button onclick="submitManualVideo()">Save Stream Forever</button>
          <button class="btn-secondary" onclick="toggleAddForm()">Cancel</button>
          <span id="manual-save-status" style="font-size: 0.85rem; color: #38bdf8;"></span>
        </div>
      </div>

      <!-- Controls Toolbar -->
      <div class="controls-bar">
        <div class="controls-left">
          <input type="text" id="library-search" placeholder="Search by title, ID, or URL..." style="width: 280px;" oninput="onSearchInput()" />
          <select id="library-source-filter" onchange="onSourceFilterChange()">
            <option value="all">Storage: All (DB & Redis)</option>
            <option value="db">Storage: PostgreSQL Database</option>
            <option value="redis">Storage: Redis Cache</option>
          </select>
        </div>
        <div class="controls-right">
          <label style="font-size: 0.82rem; color: #94a3b8;">Show per page:</label>
          <select id="library-limit-select" onchange="onLimitChange()">
            <option value="10">10 per page</option>
            <option value="25">25 per page</option>
            <option value="50">50 per page</option>
            <option value="100">100 per page</option>
          </select>
        </div>
      </div>

      <!-- Video Library List -->
      <div id="library-container">
        <div class="empty-msg">Loading stored video library...</div>
      </div>

      <!-- Pagination Footer -->
      <div class="pagination-bar">
        <div id="pagination-info" class="mono">Showing 0 of 0 videos</div>
        <div style="display: flex; gap: 8px;">
          <button class="btn-secondary btn-sm" id="btn-prev" onclick="prevPage()">Prev</button>
          <span id="page-display" class="mono" style="display: flex; align-items: center; padding: 0 8px;">Page 1 of 1</span>
          <button class="btn-secondary btn-sm" id="btn-next" onclick="nextPage()">Next</button>
        </div>
      </div>
    </div>

    <!-- Recent Completed Tasks -->
    <div class="card">
      <div class="card-header">
        <div class="card-title">
          <span>[RECENT] Completed Scrape Tasks (Past Hour)</span>
        </div>
      </div>
      <div id="recent-tasks-container">
        <div class="empty-msg">No recent tasks recorded yet.</div>
      </div>
    </div>

    <!-- Quick Test Trigger Box -->
    <div class="card">
      <div class="card-header">
        <div class="card-title">[TEST] Dispatch Direct Scrape Probe</div>
      </div>
      <p style="font-size: 0.85rem; color: #94a3b8;">Trigger a live scrape directly against the backend engine to watch the real-time processing row appear above:</p>
      <div class="input-row">
        <input type="text" id="test-title" placeholder="e.g. Up Close 4" value="Up Close 4" />
        <button onclick="triggerTestScrape()">Test Scrape</button>
      </div>
      <div id="test-result" style="margin-top: 12px; font-size: 0.85rem; color: #38bdf8;"></div>
    </div>
  </div>

  <script>
    let countdown = 3;
    let libPage = 1;
    let libLimit = 10;
    let libSource = 'all';
    let libSearch = '';
    let searchDebounce = null;

    setInterval(() => {
      countdown--;
      if (countdown <= 0) {
        countdown = 3;
        fetchTasks();
      }
      const cdEl = document.getElementById('countdown');
      if (cdEl) cdEl.innerText = countdown;
    }, 1000);

    function toggleAddForm() {
      const panel = document.getElementById('add-video-panel');
      if (panel) {
        panel.style.display = panel.style.display === 'block' ? 'none' : 'block';
      }
    }

    function onSearchInput() {
      clearTimeout(searchDebounce);
      searchDebounce = setTimeout(() => {
        const inputEl = document.getElementById('library-search');
        libSearch = inputEl ? inputEl.value.trim() : '';
        libPage = 1;
        fetchLibrary();
      }, 350);
    }

    function onSourceFilterChange() {
      const filterEl = document.getElementById('library-source-filter');
      libSource = filterEl ? filterEl.value : 'all';
      libPage = 1;
      fetchLibrary();
    }

    function onLimitChange() {
      const limitEl = document.getElementById('library-limit-select');
      libLimit = limitEl ? parseInt(limitEl.value, 10) : 10;
      libPage = 1;
      fetchLibrary();
    }

    function prevPage() {
      if (libPage > 1) {
        libPage--;
        fetchLibrary();
      }
    }

    function nextPage() {
      libPage++;
      fetchLibrary();
    }

    // Delegated click handler for copy & toggle buttons (no inline JS string quoting)
    document.addEventListener('click', function(e) {
      const target = e.target;
      if (!target) return;

      if (target.classList && target.classList.contains('copy-btn')) {
        const url = target.getAttribute('data-url');
        if (url) {
          navigator.clipboard.writeText(url).then(function() {
            const orig = target.innerText;
            target.innerText = 'Copied!';
            setTimeout(function() { target.innerText = orig; }, 1500);
          }).catch(function() {
            prompt('Copy stream URL:', url);
          });
        }
      } else if (target.classList && target.classList.contains('toggle-btn')) {
        const idx = target.getAttribute('data-idx');
        const el = document.getElementById('streams-' + idx);
        if (el) {
          if (el.style.display === 'none' || !el.style.display) {
            el.style.display = 'table-row';
            target.innerText = 'Hide Links';
          } else {
            el.style.display = 'none';
            target.innerText = 'View Links';
          }
        }
      }
    });

    async function submitManualVideo() {
      const title = document.getElementById('m-title').value.trim();
      const tpdbId = document.getElementById('m-id').value.trim();
      const streamUrl = document.getElementById('m-url').value.trim();
      const quality = document.getElementById('m-quality').value.trim() || '1080p';
      const sourceName = document.getElementById('m-source').value.trim() || 'Manual';
      const statusEl = document.getElementById('manual-save-status');

      if (!title || !streamUrl) {
        if (statusEl) statusEl.innerText = 'Error: Title and Stream URL are required.';
        return;
      }

      if (statusEl) statusEl.innerText = 'Saving permanently...';
      try {
        const res = await fetch('/api/v1/admin/videos', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ title, tpdbId, streamUrl, quality, sourceName })
        });
        const data = await res.json();
        if (data.status === 'SUCCESS') {
          if (statusEl) statusEl.innerText = 'Stream saved forever to DB & Redis!';
          document.getElementById('m-title').value = '';
          document.getElementById('m-url').value = '';
          setTimeout(() => {
            toggleAddForm();
            if (statusEl) statusEl.innerText = '';
          }, 1500);
          fetchLibrary();
        } else {
          if (statusEl) statusEl.innerText = 'Error: ' + (data.error || 'Failed to save');
        }
      } catch (err) {
        if (statusEl) statusEl.innerText = 'Network error: ' + err.message;
      }
    }

    async function fetchLibrary() {
      try {
        const url = '/api/v1/admin/videos?page=' + libPage + '&limit=' + libLimit + '&source=' + libSource + '&search=' + encodeURIComponent(libSearch);
        const res = await fetch(url);
        if (!res.ok) return;
        const data = await res.json();

        const libValEl = document.getElementById('val-library');
        if (libValEl) libValEl.innerText = data.total;

        const pageDisplayEl = document.getElementById('page-display');
        if (pageDisplayEl) pageDisplayEl.innerText = 'Page ' + data.page + ' of ' + data.totalPages;

        const btnPrev = document.getElementById('btn-prev');
        if (btnPrev) btnPrev.disabled = data.page <= 1;

        const btnNext = document.getElementById('btn-next');
        if (btnNext) btnNext.disabled = data.page >= data.totalPages;

        const startIdx = (data.page - 1) * data.limit + (data.total > 0 ? 1 : 0);
        const endIdx = Math.min(data.page * data.limit, data.total);
        const infoEl = document.getElementById('pagination-info');
        if (infoEl) infoEl.innerText = 'Showing ' + startIdx + ' - ' + endIdx + ' of ' + data.total + ' videos';

        const container = document.getElementById('library-container');
        if (!container) return;

        if (!data.items || data.items.length === 0) {
          container.innerHTML = '<div class="empty-msg">No videos found matching your filters.</div>';
          return;
        }

        let html = '<table><thead><tr><th>Movie Title (Clean)</th><th>Identifier / Slug</th><th>Storage</th><th>Streams</th><th>Action</th></tr></thead><tbody>';

        data.items.forEach((item, idx) => {
          let storageBadge = 'badge-hybrid';
          if (item.storage === 'DB') storageBadge = 'badge-db';
          else if (item.storage === 'Redis') storageBadge = 'badge-redis';

          html += '<tr>' +
            '<td><strong>' + escapeHtml(item.title) + '</strong></td>' +
            '<td class="mono">' + escapeHtml(item.id) + '</td>' +
            '<td><span class="badge ' + storageBadge + '">' + item.storage + '</span></td>' +
            '<td class="mono"><b>' + item.streams.length + '</b> links</td>' +
            '<td><button class="btn-secondary btn-sm toggle-btn" data-idx="' + idx + '">View Links</button></td>' +
          '</tr>';

          // Hidden Streams Drawer
          html += '<tr id="streams-' + idx + '" style="display: none;"><td colspan="5">' +
            '<div class="stream-drawer">' +
            '<div style="font-size: 0.78rem; font-weight: 600; color: #94a3b8; text-transform: uppercase; margin-bottom: 8px;">Available Streams (' + item.streams.length + ')</div>';

          if (item.streams.length === 0) {
            html += '<div style="font-size: 0.8rem; color: #64748b; font-style: italic;">No stream links available for this title.</div>';
          } else {
            item.streams.forEach((s) => {
              const isTorrent = s.url.startsWith('magnet:') || s.url.endsWith('.torrent');
              const formatLabel = isTorrent ? 'TORRENT' : (s.isM3u8 ? 'HLS / M3U8' : 'DIRECT MP4');

              html += '<div class="stream-row">' +
                '<div class="stream-left">' +
                  '<span class="badge badge-qual">' + escapeHtml(s.quality) + '</span>' +
                  '<span class="badge" style="background: #172554; color: #93c5fd; border: 1px solid #1e40af;">' + escapeHtml(s.source) + '</span>' +
                  '<span class="badge" style="background: #1e1b4b; color: #a5b4fc; border: 1px solid #3730a3;">' + formatLabel + '</span>' +
                  '<a href="' + escapeHtml(s.url) + '" target="_blank" rel="noopener noreferrer" class="stream-url-text" title="' + escapeHtml(s.url) + '">' + escapeHtml(s.url) + '</a>' +
                '</div>' +
                '<div style="display: flex; gap: 6px;">' +
                  '<button class="btn-secondary btn-sm copy-btn" data-url="' + escapeHtml(s.url) + '">Copy</button>' +
                  '<a href="' + escapeHtml(s.url) + '" target="_blank" rel="noopener noreferrer" class="btn btn-sm">Open</a>' +
                '</div>' +
              '</div>';
            });
          }

          html += '</div></td></tr>';
        });

        html += '</tbody></table>';
        container.innerHTML = html;
      } catch (err) {
        console.error('Library fetch error:', err);
      }
    }

    function escapeHtml(str) {
      if (!str) return '';
      return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    async function fetchTasks() {
      try {
        const res = await fetch('/api/v1/admin/tasks');
        if (!res.ok) return;
        const data = await res.json();

        const vAct = document.getElementById('val-active');
        if (vAct) vAct.innerText = data.activeTasks.length;

        const vWait = document.getElementById('val-waiting');
        if (vWait) vWait.innerText = (data.queue && data.queue.waiting) || 0;

        const vRec = document.getElementById('val-recent');
        if (vRec) vRec.innerText = data.recentTasks.length;

        const vFail = document.getElementById('val-failed');
        if (vFail) vFail.innerText = (data.queue && data.queue.failed) || 0;

        const pill = document.getElementById('active-count-pill');
        if (pill) pill.innerText = data.activeTasks.length + ' Active';

        const activeContainer = document.getElementById('active-tasks-container');
        if (activeContainer) {
          if (data.activeTasks.length === 0) {
            activeContainer.innerHTML = '<div class="empty-msg">No active scrapes running right now. Ready for incoming requests.</div>';
          } else {
            let html = '<table><thead><tr><th>Movie Title</th><th>TPDB ID</th><th>Status</th><th>Started At</th><th>Elapsed</th></tr></thead><tbody>';
            for (const t of data.activeTasks) {
              const elapsed = Math.round((Date.now() - new Date(t.startedAt).getTime()) / 1000);
              html += '<tr>' +
                '<td><strong>' + escapeHtml(t.name || 'Unknown') + '</strong></td>' +
                '<td class="mono">' + escapeHtml(t.id || 'N/A') + '</td>' +
                '<td><span class="badge badge-processing">SCRAPING</span></td>' +
                '<td class="mono">' + new Date(t.startedAt).toLocaleTimeString() + '</td>' +
                '<td class="mono">' + elapsed + 's ago</td>' +
              '</tr>';
            }
            html += '</tbody></table>';
            activeContainer.innerHTML = html;
          }
        }

        const recentContainer = document.getElementById('recent-tasks-container');
        if (recentContainer) {
          if (data.recentTasks.length === 0) {
            recentContainer.innerHTML = '<div class="empty-msg">No recent tasks recorded yet.</div>';
          } else {
            let html = '<table><thead><tr><th>Movie Title</th><th>TPDB ID</th><th>Status</th><th>Streams Found</th><th>Finished</th></tr></thead><tbody>';
            for (const t of data.recentTasks) {
              let badgeClass = 'badge-empty';
              if (t.status === 'SUCCESS' && t.streamCount > 0) badgeClass = 'badge-success';
              else if (t.status === 'FAILED') badgeClass = 'badge-failed';

              html += '<tr>' +
                '<td><strong>' + escapeHtml(t.name || 'Unknown') + '</strong></td>' +
                '<td class="mono">' + escapeHtml(t.id || 'N/A') + '</td>' +
                '<td><span class="badge ' + badgeClass + '">' + (t.status || 'DONE') + '</span></td>' +
                '<td class="mono"><b>' + (t.streamCount || 0) + '</b> streams</td>' +
                '<td class="mono">' + (t.finishedAt ? new Date(t.finishedAt).toLocaleTimeString() : 'N/A') + '</td>' +
              '</tr>';
            }
            html += '</tbody></table>';
            recentContainer.innerHTML = html;
          }
        }
      } catch (err) {
        console.error('Fetch error:', err);
      }
    }

    async function triggerTestScrape() {
      const title = document.getElementById('test-title').value.trim();
      if (!title) return;
      const resEl = document.getElementById('test-result');
      if (resEl) resEl.innerText = 'Triggering test scrape for: ' + title + '...';
      const fakeId = 'diag_' + Date.now().toString().slice(-6);
      fetch('/api/v1/streams/movie/' + fakeId + '?title=' + encodeURIComponent(title))
        .then(r => r.json())
        .then(data => {
          if (resEl) resEl.innerText = 'Completed! Streams found: ' + (data.results ? data.results.length : 0);
          fetchTasks();
          fetchLibrary();
        })
        .catch(err => {
          if (resEl) resEl.innerText = 'Error: ' + err.message;
        });
      setTimeout(fetchTasks, 300);
    }

    // Initial loads
    fetchTasks();
    fetchLibrary();
  </script>
</body>
</html>`;

    reply.header('Content-Type', 'text/html; charset=utf-8');
    return reply.send(html);
  });

  // API v1 Modules
  await fastify.register(streamsRoutes, { prefix: '/api/v1' });
  await fastify.register(adminRoutes, { prefix: '/api/v1/admin' });

  return fastify;
}

async function start() {
  await initRedis();
  await startWorker();
  const app = await buildApp();

  const shutdown = async () => {
    logger.info('Shutting down API server...');
    await app.close();
    await stopWorker();
    await closeRedis();
    await closeDb();
    process.exit(0);
  };

  process.on('SIGTERM', shutdown);
  process.on('SIGINT', shutdown);

  try {
    await app.listen({ port: env.PORT, host: env.HOST });
    logger.info(`Server listening at http://${env.HOST}:${env.PORT}`);
  } catch (err) {
    logger.error({ err }, 'Error starting server');
    process.exit(1);
  }
}

if (process.env.NODE_ENV !== 'test') {
  start();
}
