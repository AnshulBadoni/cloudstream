import Fastify from 'fastify';
import cors from '@fastify/cors';
import rateLimit from '@fastify/rate-limit';
import { env } from './config/env.js';
import { logger } from './utils/logger.js';
import { initRedis, closeRedis, redisCache } from './redis/clients.js';
import { closeDb } from './db/client.js';
import { streamsRoutes } from './modules/streams/streams.routes.js';
import { adminRoutes } from './modules/admin/admin.routes.js';
import { register, httpRequestDurationMicroseconds } from './metrics/metrics.js';
import { startWorker, stopWorker } from './queue/discovery.worker.js';
import { discoveryQueue } from './queue/discovery.queue.js';
import { SpeedPornAdapter } from './modules/discovery/adapters/speedporn.adapter.js';
import { ParadiseHillAdapter } from './modules/discovery/adapters/paradisehill.adapter.js';
import { PornoTorrentAdapter } from './modules/discovery/adapters/pornotorrent.adapter.js';
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
      circuitBreaker: {
        speedporn: circuitBreaker.isOpen('speedporn'),
        paradisehill: circuitBreaker.isOpen('paradisehill'),
        pornotorrent: circuitBreaker.isOpen('pornotorrent'),
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

  // Web Dashboard for Monitoring Scraping & Queue Status
  fastify.get('/dashboard', async (req, reply) => {
    const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>CloudStream Discovery & Task Monitor</title>
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
    .container { max-width: 1200px; margin: 0 auto; }
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
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
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
    .metric-title { font-size: 0.85rem; color: #94a3b8; margin-bottom: 6px; text-transform: uppercase; letter-spacing: 0.05em; }
    .metric-value { font-size: 2rem; font-weight: 700; color: #f1f5f9; font-family: 'JetBrains Mono', monospace; }
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
    .mono { font-family: 'JetBrains Mono', monospace; font-size: 0.8rem; }
    .empty-msg { text-align: center; padding: 32px; color: #64748b; font-style: italic; }
    .refresh-bar {
      display: flex;
      align-items: center;
      gap: 12px;
      font-size: 0.85rem;
      color: #94a3b8;
    }
    button {
      background: #2563eb;
      color: #fff;
      border: none;
      padding: 6px 14px;
      border-radius: 6px;
      font-weight: 600;
      cursor: pointer;
      font-size: 0.85rem;
    }
    button:hover { background: #1d4ed8; }
    .input-row { display: flex; gap: 8px; margin-top: 12px; }
    input[type="text"] {
      flex: 1;
      background: #090d16;
      border: 1px solid #243048;
      border-radius: 6px;
      padding: 8px 12px;
      color: #f1f5f9;
      font-size: 0.9rem;
    }
    input[type="text"]:focus { outline: none; border-color: #38bdf8; }
  </style>
</head>
<body>
  <div class="container">
    <header>
      <div class="logo-area">
        <div class="pulse-dot"></div>
        <h1>CloudStream Task & Scrape Monitor</h1>
        <span class="status-badge">System Live</span>
      </div>
      <div class="refresh-bar">
        <span>Auto-refreshing in <b id="countdown" class="mono">3</b>s</span>
        <button onclick="fetchTasks()">Refresh Now</button>
      </div>
    </header>

    <div class="metrics-grid">
      <div class="metric-card">
        <div class="metric-title">Active In-Flight</div>
        <div class="metric-value" id="val-active" style="color: #38bdf8;">0</div>
        <div class="metric-desc">Live scraping tasks in progress</div>
      </div>
      <div class="metric-card">
        <div class="metric-title">Queue Waiting</div>
        <div class="metric-value" id="val-waiting">0</div>
        <div class="metric-desc">Jobs queued in BullMQ</div>
      </div>
      <div class="metric-card">
        <div class="metric-title">Recent Finished</div>
        <div class="metric-value" id="val-recent" style="color: #4ade80;">0</div>
        <div class="metric-desc">Cached results in past hour</div>
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
          <span>⚡ Active Tasks Currently Scraping</span>
          <span id="active-count-pill" class="badge badge-processing">0 Active</span>
        </div>
      </div>
      <div id="active-tasks-container">
        <div class="empty-msg">No active scrapes running right now. Ready for incoming requests.</div>
      </div>
    </div>

    <!-- Recent Completed Tasks -->
    <div class="card">
      <div class="card-header">
        <div class="card-title">
          <span>🕒 Recently Scraped Movies (Past Hour)</span>
        </div>
      </div>
      <div id="recent-tasks-container">
        <div class="empty-msg">No recent tasks recorded yet.</div>
      </div>
    </div>

    <!-- Quick Test Trigger Box -->
    <div class="card">
      <div class="card-header">
        <div class="card-title">🚀 Dispatch Quick Test Scrape</div>
      </div>
      <p style="font-size: 0.85rem; color: #94a3b8;">Trigger a live scrape directly against the backend engine to watch the real-time processing row appear above:</p>
      <div class="input-row">
        <input type="text" id="test-title" placeholder="e.g. How I Met My Girlfriend: Chanel Preston" value="How I Met My Girlfriend: Chanel Preston" />
        <button onclick="triggerTestScrape()">Test Scrape</button>
      </div>
      <div id="test-result" style="margin-top: 12px; font-size: 0.85rem; color: #38bdf8;"></div>
    </div>
  </div>

  <script>
    let countdown = 3;
    setInterval(() => {
      countdown--;
      if (countdown <= 0) {
        countdown = 3;
        fetchTasks();
      }
      document.getElementById('countdown').innerText = countdown;
    }, 1000);

    async function fetchTasks() {
      try {
        const res = await fetch('/api/v1/admin/tasks');
        if (!res.ok) return;
        const data = await res.json();

        // Update KPIs
        document.getElementById('val-active').innerText = data.activeTasks.length;
        document.getElementById('val-waiting').innerText = data.queue.waiting || 0;
        document.getElementById('val-recent').innerText = data.recentTasks.length;
        document.getElementById('val-failed').innerText = data.queue.failed || 0;
        document.getElementById('active-count-pill').innerText = data.activeTasks.length + ' Active';

        // Render Active Tasks
        const activeContainer = document.getElementById('active-tasks-container');
        if (data.activeTasks.length === 0) {
          activeContainer.innerHTML = '<div class="empty-msg">No active scrapes running right now. Ready for incoming requests.</div>';
        } else {
          let html = '<table><thead><tr><th>Movie Title</th><th>TPDB ID</th><th>Status</th><th>Started At</th><th>Elapsed</th></tr></thead><tbody>';
          for (const t of data.activeTasks) {
            const elapsed = Math.round((Date.now() - new Date(t.startedAt).getTime()) / 1000);
            html += '<tr>' +
              '<td><strong>' + (t.name || 'Unknown') + '</strong></td>' +
              '<td class="mono">' + (t.id || 'N/A') + '</td>' +
              '<td><span class="badge badge-processing">SCRAPING</span></td>' +
              '<td class="mono">' + new Date(t.startedAt).toLocaleTimeString() + '</td>' +
              '<td class="mono">' + elapsed + 's ago</td>' +
            '</tr>';
          }
          html += '</tbody></table>';
          activeContainer.innerHTML = html;
        }

        // Render Recent Tasks
        const recentContainer = document.getElementById('recent-tasks-container');
        if (data.recentTasks.length === 0) {
          recentContainer.innerHTML = '<div class="empty-msg">No recent tasks recorded yet.</div>';
        } else {
          let html = '<table><thead><tr><th>Movie Title</th><th>TPDB ID</th><th>Status</th><th>Streams Found</th><th>Finished</th></tr></thead><tbody>';
          for (const t of data.recentTasks) {
            let badgeClass = 'badge-empty';
            if (t.status === 'SUCCESS' && t.streamCount > 0) badgeClass = 'badge-success';
            else if (t.status === 'FAILED') badgeClass = 'badge-failed';

            html += '<tr>' +
              '<td><strong>' + (t.name || 'Unknown') + '</strong></td>' +
              '<td class="mono">' + (t.id || 'N/A') + '</td>' +
              '<td><span class="badge ' + badgeClass + '">' + (t.status || 'DONE') + '</span></td>' +
              '<td class="mono"><b>' + (t.streamCount || 0) + '</b> streams</td>' +
              '<td class="mono">' + (t.finishedAt ? new Date(t.finishedAt).toLocaleTimeString() : 'N/A') + '</td>' +
            '</tr>';
          }
          html += '</tbody></table>';
          recentContainer.innerHTML = html;
        }
      } catch (err) {
        console.error('Fetch error:', err);
      }
    }

    async function triggerTestScrape() {
      const title = document.getElementById('test-title').value.trim();
      if (!title) return;
      const resEl = document.getElementById('test-result');
      resEl.innerText = 'Triggering test scrape for: ' + title + '...';
      const fakeId = 'diag_' + Date.now().toString().slice(-6);
      fetch('/api/v1/streams/movie/' + fakeId + '?title=' + encodeURIComponent(title))
        .then(r => r.json())
        .then(data => {
          resEl.innerText = 'Completed! Streams found: ' + (data.results ? data.results.length : 0);
          fetchTasks();
        })
        .catch(err => {
          resEl.innerText = 'Error: ' + err.message;
        });
      // Immediate fetch to see processing row appear
      setTimeout(fetchTasks, 300);
    }

    // Initial fetch on page load
    fetchTasks();
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
