import { redisQueue } from '../../redis/clients.js';
import { getDb } from '../../db/client.js';
import { content, releases, sources } from '../../db/schema.js';
import { logger } from '../../utils/logger.js';

export const WAL_STREAM_KEY = 'wal:postgres:writes';
export const WAL_GROUP_NAME = 'db_syncer_group';

export async function appendToDbWal(payload: {
  content: typeof content.$inferInsert;
  releases: (typeof releases.$inferInsert)[];
  sources: (typeof sources.$inferInsert)[];
}) {
  try {
    await redisQueue.xadd(
      WAL_STREAM_KEY,
      '*',
      'payload',
      JSON.stringify(payload),
      'timestamp',
      Date.now().toString()
    );
    logger.warn({ tpdbId: payload.content.tpdbId }, 'Appended discovery write to durable Redis Stream WAL');
  } catch (err) {
    logger.error({ err }, 'CRITICAL: Failed appending write to Redis Stream WAL');
  }
}

export async function processDbWal(signal?: AbortSignal) {
  const db = getDb();

  // Create consumer group if not already present
  try {
    await redisQueue.xgroup('CREATE', WAL_STREAM_KEY, WAL_GROUP_NAME, '0', 'MKSTREAM');
  } catch (err: any) {
    if (!err.message.includes('BUSYGROUP')) {
      logger.error({ err }, 'Error creating WAL consumer group');
    }
  }

  logger.info('DB WAL Syncer consumer loop active');

  while (!signal?.aborted) {
    try {
      const response: any = await (redisQueue as any).xreadgroup(
        'GROUP',
        WAL_GROUP_NAME,
        'worker_1',
        'COUNT',
        25,
        'BLOCK',
        3000,
        'STREAMS',
        WAL_STREAM_KEY,
        '>'
      );

      if (!response || !response.length) continue;

      for (const [_, entries] of response) {
        for (const [id, fields] of entries) {
          const payloadIndex = fields.indexOf('payload');
          if (payloadIndex === -1) continue;

          const data = JSON.parse(fields[payloadIndex + 1]);

          try {
            await db.transaction(async (tx) => {
              const [c] = await tx
                .insert(content)
                .values(data.content)
                .onConflictDoUpdate({
                  target: content.tpdbId,
                  set: { updatedAt: new Date(), title: data.content.title },
                })
                .returning();

              for (const rel of data.releases) {
                rel.contentId = c.id;
                const [r] = await tx.insert(releases).values(rel).returning();

                for (const src of data.sources) {
                  src.releaseId = r.id;
                  await tx.insert(sources).values(src);
                }
              }
            });

            // Acknowledge and trim after successful commit
            await redisQueue.xack(WAL_STREAM_KEY, WAL_GROUP_NAME, id);
            await redisQueue.xdel(WAL_STREAM_KEY, id);
          } catch (writeErr) {
            logger.error({ err: writeErr, id }, 'Postgres write failed during WAL replay; will retry next loop');
            await new Promise((r) => setTimeout(r, 5000));
            break;
          }
        }
      }
    } catch (loopErr) {
      logger.error({ err: loopErr }, 'Error in WAL processing loop');
      await new Promise((r) => setTimeout(r, 3000));
    }
  }
}
