import { Queue } from 'bullmq';
import { redisQueue } from '../redis/clients.js';

export const DISCOVERY_QUEUE_NAME = 'discovery_tasks';

export const discoveryQueue = new Queue(DISCOVERY_QUEUE_NAME, {
  connection: redisQueue,
  defaultJobOptions: {
    attempts: 3,
    backoff: {
      type: 'exponential',
      delay: 2000,
    },
    removeOnComplete: {
      count: 1000,
      age: 3600,
    },
    removeOnFail: {
      count: 2000,
      age: 86400,
    },
  },
});
