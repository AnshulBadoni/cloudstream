import { drizzle } from 'drizzle-orm/postgres-js';
import postgres from 'postgres';
import * as schema from './schema.js';
import { env } from '../config/env.js';
import { logger } from '../utils/logger.js';

let queryClient: ReturnType<typeof postgres> | null = null;
let dbInstance: ReturnType<typeof drizzle<typeof schema>> | null = null;

export function getDb() {
  if (!dbInstance) {
    queryClient = postgres(env.DATABASE_URL, {
      max: 20,
      idle_timeout: 30,
      connect_timeout: 5,
      onnotice: () => {},
    });

    dbInstance = drizzle(queryClient, { schema });
    logger.info('PostgreSQL Drizzle client initialized');
  }
  return dbInstance;
}

export async function closeDb() {
  if (queryClient) {
    await queryClient.end();
    queryClient = null;
    dbInstance = null;
  }
}
