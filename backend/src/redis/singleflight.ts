import crypto from 'node:crypto';
import { redisCache, redisSub } from './clients.js';
import { DistributedLock } from './lock.js';
import { logger } from '../utils/logger.js';

export class LocalSingleflight {
  private inFlight = new Map<string, Promise<any>>();

  async do<T>(key: string, fn: () => Promise<T>): Promise<T> {
    const existing = this.inFlight.get(key);
    if (existing) {
      return existing;
    }

    const promise = fn().finally(() => {
      this.inFlight.delete(key);
    });

    this.inFlight.set(key, promise);
    return promise;
  }
}

export class DistributedCoalescer {
  private localSf = new LocalSingleflight();

  /**
   * Executes leader function if lock is acquired, otherwise awaits completion via Redis Pub/Sub.
   */
  async executeOrWait<T>(
    key: string,
    executeLeader: () => Promise<T>,
    timeoutMs: number = 9500
  ): Promise<T> {
    return this.localSf.do(key, async () => {
      const lockKey = `str:${key}`;
      const channel = `ch:str:${key}`;

      const lock = await DistributedLock.acquire(lockKey, 6000, 15000);

      if (lock.acquired) {
        // LEADER PROCESS
        try {
          const result = await executeLeader();
          await redisCache.publish(channel, 'READY').catch(() => {});
          return result;
        } finally {
          await lock.release();
        }
      } else {
        // FOLLOWER PROCESS (Awaits pub/sub notification from Leader)
        return new Promise<T>((resolve, reject) => {
          let timeoutHandle: NodeJS.Timeout;

          const onMessage = async (ch: string, message: string) => {
            if (ch === channel && message === 'READY') {
              cleanup();
              try {
                const cached = await redisCache.get(`str:${key}`);
                if (cached) {
                  resolve(JSON.parse(cached));
                } else {
                  resolve([] as unknown as T);
                }
              } catch (e) {
                reject(e);
              }
            }
          };

          const cleanup = () => {
            clearTimeout(timeoutHandle);
            redisSub.unsubscribe(channel).catch(() => {});
            redisSub.removeListener('message', onMessage);
          };

          timeoutHandle = setTimeout(async () => {
            cleanup();
            // Fallback read from cache on timeout
            try {
              const cached = await redisCache.get(`str:${key}`);
              resolve(cached ? JSON.parse(cached) : ([] as unknown as T));
            } catch (e) {
              reject(e);
            }
          }, timeoutMs);

          redisSub
            .subscribe(channel)
            .then(() => {
              redisSub.on('message', onMessage);
            })
            .catch((err) => {
              cleanup();
              logger.warn({ err, channel }, 'Failed to subscribe to coalescer pub/sub channel');
              // Fallback directly to reading cache
              redisCache
                .get(`str:${key}`)
                .then((cached) => resolve(cached ? JSON.parse(cached) : ([] as unknown as T)))
                .catch(reject);
            });
        });
      }
    });
  }
}

export const coalescer = new DistributedCoalescer();
