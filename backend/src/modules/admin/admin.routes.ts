import { FastifyInstance, FastifyPluginAsync } from 'fastify';
import { redisCache } from '../../redis/clients.js';
import { env } from '../../config/env.js';
import { discoveryQueue } from '../../queue/discovery.queue.js';
import { tpdbClient } from '../content/tpdb.client.js';

export const adminRoutes: FastifyPluginAsync = async (fastify: FastifyInstance) => {
  fastify.addHook('preHandler', async (request, reply) => {
    const authHeader = request.headers.authorization;
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
      return reply.status(401).send({ error: 'Unauthorized: Missing or invalid bearer token' });
    }
    const token = authHeader.substring(7);
    if (token !== env.ADMIN_API_KEY) {
      return reply.status(403).send({ error: 'Forbidden: Invalid admin credentials' });
    }
  });

  fastify.post<{ Params: { tpdbId: string } }>(
    '/refresh/:tpdbId',
    async (request, reply) => {
      const { tpdbId } = request.params;

      // Invalidate caches
      await Promise.all([
        redisCache.del(`str:m:${tpdbId}`),
        redisCache.del(`neg:m:${tpdbId}`),
      ]);

      const metadata = await tpdbClient.getScene(tpdbId);
      const title = metadata?.title || `Scene ${tpdbId}`;

      // Dispatch deep discovery to BullMQ
      const job = await discoveryQueue.add(
        'admin_force_refresh',
        {
          query: {
            tpdbId,
            contentType: 'MOVIE',
            title,
            cleanTitle: metadata?.cleanTitle || title,
            aliases: metadata?.aliases || [],
          },
        },
        { attempts: 3 }
      );

      return reply.send({
        status: 'SUCCESS',
        message: `Refresh triggered for TPDB ID ${tpdbId}`,
        jobId: job.id,
      });
    }
  );
};
