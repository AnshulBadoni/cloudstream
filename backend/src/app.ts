import Fastify from 'fastify';
import cors from '@fastify/cors';
import rateLimit from '@fastify/rate-limit';
import { env } from './config/env.js';
import { logger } from './utils/logger.js';
import { initRedis, closeRedis } from './redis/clients.js';
import { closeDb } from './db/client.js';
import { streamsRoutes } from './modules/streams/streams.routes.js';
import { adminRoutes } from './modules/admin/admin.routes.js';
import { register, httpRequestDurationMicroseconds } from './metrics/metrics.js';
import { startWorker, stopWorker } from './queue/discovery.worker.js';
import { SpeedPornAdapter } from './modules/discovery/adapters/speedporn.adapter.js';
import { ParadiseHillAdapter } from './modules/discovery/adapters/paradisehill.adapter.js';
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

    return {
      term,
      speedporn: { durationMs: spDuration, streamCount: spResults.length, streams: spResults, error: spError },
      paradisehill: { durationMs: phDuration, streamCount: phResults.length, streams: phResults, error: phError },
      circuitBreaker: {
        speedporn: circuitBreaker.isOpen('speedporn'),
        paradisehill: circuitBreaker.isOpen('paradisehill'),
      },
    };
  });

  // Prometheus Metrics endpoint
  fastify.get('/metrics', async (req, reply) => {
    reply.header('Content-Type', register.contentType);
    return reply.send(await register.metrics());
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
