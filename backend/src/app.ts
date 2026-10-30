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
  const app = await buildApp();

  const shutdown = async () => {
    logger.info('Shutting down API server...');
    await app.close();
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
