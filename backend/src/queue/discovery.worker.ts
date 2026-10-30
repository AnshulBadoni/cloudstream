import { Worker } from 'bullmq';
import { redisQueue } from '../redis/clients.js';
import { DISCOVERY_QUEUE_NAME } from './discovery.queue.js';
import { discoveryOrchestrator } from '../modules/discovery/orchestrator.service.js';
import { processDbWal } from '../modules/discovery/wal-sync.service.js';
import { env } from '../config/env.js';
import { logger } from '../utils/logger.js';
import { initRedis, closeRedis } from '../redis/clients.js';

async function bootstrapWorker() {
  await initRedis();

  // Start background DB WAL Syncer
  processDbWal().catch((err) => logger.error({ err }, 'Background WAL Syncer failed'));

  const worker = new Worker(
    DISCOVERY_QUEUE_NAME,
    async (job) => {
      logger.info({ jobId: job.id, data: job.data }, 'Processing background discovery job');
      const { query } = job.data;
      if (!query) return;

      // Allow 15s deep crawl in background
      await discoveryOrchestrator.discover(query, 15000);
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

  const shutdown = async () => {
    logger.info('Shutting down BullMQ worker...');
    await worker.close();
    await closeRedis();
    process.exit(0);
  };

  process.on('SIGTERM', shutdown);
  process.on('SIGINT', shutdown);

  logger.info(`BullMQ Worker running with global concurrency ${env.WORKER_GLOBAL_CONCURRENCY}`);
}

bootstrapWorker().catch((err) => {
  logger.error({ err }, 'Fatal error bootstrapping worker');
  process.exit(1);
});
