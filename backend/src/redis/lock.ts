import crypto from 'node:crypto';
import { redisCache } from './clients.js';
import { logger } from '../utils/logger.js';

const RENEW_LUA = `
  if redis.call("get", KEYS[1]) == ARGV[1] then
    return redis.call("pexpire", KEYS[1], ARGV[2])
  else
    return 0
  end
`;

const RELEASE_LUA = `
  if redis.call("get", KEYS[1]) == ARGV[1] then
    return redis.call("del", KEYS[1])
  else
    return 0
  end
`;

export interface LockHandle {
  key: string;
  token: string;
  acquired: boolean;
  release: () => Promise<boolean>;
}

export class DistributedLock {
  private static nodeId = `${process.pid}_${crypto.randomBytes(4).toString('hex')}`;

  /**
   * Acquire a distributed lock with automatic lease heartbeating.
   * @param key Lock resource name
   * @param ttlMs Initial lease duration (default: 6000ms)
   * @param maxLifetimeMs Maximum absolute duration before heartbeat auto-stops (default: 15000ms)
   */
  static async acquire(
    key: string,
    ttlMs: number = 6000,
    maxLifetimeMs: number = 15000
  ): Promise<LockHandle> {
    const lockKey = `lock:${key}`;
    const token = `${this.nodeId}:${crypto.randomUUID()}`;

    const res = await redisCache.set(lockKey, token, 'PX', ttlMs, 'NX');
    if (res !== 'OK') {
      return {
        key: lockKey,
        token,
        acquired: false,
        release: async () => false,
      };
    }

    let heartbeatTimer: NodeJS.Timeout | null = null;
    const startTime = Date.now();

    // Start background lease renewal heartbeat every ttlMs / 3
    const heartbeatInterval = Math.max(1000, Math.floor(ttlMs / 3));
    heartbeatTimer = setInterval(async () => {
      if (Date.now() - startTime >= maxLifetimeMs) {
        logger.warn({ lockKey }, 'Lock lease reached maximum lifetime safety cap. Aborting renewal.');
        if (heartbeatTimer) clearInterval(heartbeatTimer);
        return;
      }

      try {
        const renewed = await redisCache.eval(RENEW_LUA, 1, lockKey, token, ttlMs);
        if (renewed !== 1) {
          logger.warn({ lockKey }, 'Lock lease renewal failed: ownership lost.');
          if (heartbeatTimer) clearInterval(heartbeatTimer);
        }
      } catch (err) {
        logger.error({ err, lockKey }, 'Error executing lock lease renewal');
      }
    }, heartbeatInterval);

    const release = async (): Promise<boolean> => {
      if (heartbeatTimer) {
        clearInterval(heartbeatTimer);
        heartbeatTimer = null;
      }

      try {
        const delRes = await redisCache.eval(RELEASE_LUA, 1, lockKey, token);
        return delRes === 1;
      } catch (err) {
        logger.error({ err, lockKey }, 'Error releasing distributed lock');
        return false;
      }
    };

    return {
      key: lockKey,
      token,
      acquired: true,
      release,
    };
  }
}
