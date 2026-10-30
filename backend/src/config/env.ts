import { z } from 'zod';
import dotenv from 'dotenv';

dotenv.config();

const envSchema = z.object({
  NODE_ENV: z.enum(['development', 'production', 'test']).default('development'),
  PORT: z.coerce.number().default(3000),
  HOST: z.string().default('0.0.0.0'),
  DATABASE_URL: z.string().default('postgres://cs_user:cs_pass@localhost:5432/cloudstream_db'),
  REDIS_CACHE_URL: z.string().default('redis://localhost:6379'),
  REDIS_QUEUE_URL: z.string().default('redis://localhost:6380'),
  TPDB_API_KEY: z.string().default(''),
  ADMIN_API_KEY: z.string().default('secret_admin_token'),
  WORKER_GLOBAL_CONCURRENCY: z.coerce.number().default(16),
  SYNC_DISCOVERY_TIMEOUT_MS: z.coerce.number().default(3800),
});

export type Env = z.infer<typeof envSchema>;
export const env = envSchema.parse(process.env);
