import { Redis } from 'ioredis';
import { env } from '../config/env.js';
import { logger } from '../utils/logger.js';

export const redisCache = new Redis(env.REDIS_CACHE_URL, {
  maxRetriesPerRequest: 3,
  lazyConnect: true,
  enableReadyCheck: true,
});

export const redisSub = new Redis(env.REDIS_CACHE_URL, {
  maxRetriesPerRequest: null,
  lazyConnect: true,
});

export const redisQueue = new Redis(env.REDIS_QUEUE_URL, {
  maxRetriesPerRequest: null, // Required by BullMQ
  lazyConnect: true,
});

redisCache.on('error', (err) => logger.error({ err }, 'Redis Cache Error'));
redisSub.on('error', (err) => logger.error({ err }, 'Redis Sub Error'));
redisQueue.on('error', (err) => logger.error({ err }, 'Redis Queue Error'));

export async function initRedis() {
  await Promise.all([
    redisCache.connect().catch((err) => logger.warn({ err }, 'Redis Cache initial connect skipped/failed')),
    redisSub.connect().catch((err) => logger.warn({ err }, 'Redis Sub initial connect skipped/failed')),
    redisQueue.connect().catch((err) => logger.warn({ err }, 'Redis Queue initial connect skipped/failed')),
  ]);
  logger.info('Redis connections ready');
}

export async function closeRedis() {
  await Promise.all([
    redisCache.quit().catch(() => {}),
    redisSub.quit().catch(() => {}),
    redisQueue.quit().catch(() => {}),
  ]);
}
