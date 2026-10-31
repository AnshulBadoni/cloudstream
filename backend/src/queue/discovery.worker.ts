import { Worker } from 'bullmq';
import { redisQueue, redisCache } from '../redis/clients.js';
import { DISCOVERY_QUEUE_NAME } from './discovery.queue.js';
import { discoveryOrchestrator } from '../modules/discovery/orchestrator.service.js';
import { processDbWal } from '../modules/discovery/wal-sync.service.js';
import { env } from '../config/env.js';
import { logger } from '../utils/logger.js';
import { initRedis, closeRedis } from '../redis/clients.js';

let worker: Worker | null = null;

export async function startWorker(): Promise<Worker> {
  // Start background DB WAL Syncer
  processDbWal().catch((err) => logger.error({ err }, 'Background WAL Syncer failed'));

  worker = new Worker(
    DISCOVERY_QUEUE_NAME,
    async (job) => {
      logger.info({ jobId: job.id, data: job.data }, 'Processing background discovery job');
      const { query } = job.data;
      if (!query) return;

      const taskInfo = {
        id: query.tpdbId,
        name: query.title,
        isProcessing: true,
        status: 'PROCESSING',
        jobId: job.id,
        startedAt: new Date().toISOString(),
      };
      await redisCache.set(`task:m:${query.tpdbId}`, JSON.stringify(taskInfo), 'EX', 180).catch(() => {});
      await redisCache.hset('active_tasks', query.tpdbId, JSON.stringify(taskInfo)).catch(() => {});

      try {
        // Allow 15s deep crawl in background
        const res = await discoveryOrchestrator.discover(query, 15000);
        await redisCache.set(`task:m:${query.tpdbId}`, JSON.stringify({
          id: query.tpdbId,
          name: query.title,
          isProcessing: false,
          status: res.status,
          streamCount: res.streams.length,
          finishedAt: new Date().toISOString(),
        }), 'EX', 3600).catch(() => {});
      } catch (err: any) {
        await redisCache.set(`task:m:${query.tpdbId}`, JSON.stringify({
          id: query.tpdbId,
          name: query.title,
          isProcessing: false,
          status: 'FAILED',
          error: err?.message,
          finishedAt: new Date().toISOString(),
        }), 'EX', 3600).catch(() => {});
        throw err;
      } finally {
        await redisCache.hdel('active_tasks', query.tpdbId).catch(() => {});
      }
    },
    {
      connection: redisQueue,
      concurrency: env.WORKER_GLOBAL_CONCURRENCY,
      limiter: {
        max: 20,
        duration: 1000,
      },
    }
  );

  worker.on('completed', (job) => logger.info({ jobId: job.id }, 'Job completed successfully'));
  worker.on('failed', (job, err) => logger.error({ jobId: job?.id, err }, 'Job failed'));

  logger.info(`BullMQ Worker running with global concurrency ${env.WORKER_GLOBAL_CONCURRENCY}`);
  return worker;
}

export async function stopWorker(): Promise<void> {
  if (worker) {
    logger.info('Shutting down BullMQ worker...');
    await worker.close();
    worker = null;
  }
}

// Support running standalone if invoked directly (e.g. `npm run worker`)
if (process.argv[1]?.endsWith('discovery.worker.ts') || process.argv[1]?.endsWith('discovery.worker.js')) {
  initRedis().then(startWorker).catch((err) => {
    logger.error({ err }, 'Fatal error bootstrapping worker');
    process.exit(1);
  });
}
