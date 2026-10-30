import client from 'prom-client';

const collectDefaultMetrics = client.collectDefaultMetrics;
collectDefaultMetrics({ prefix: 'cloudstream_engine_' });

export const httpRequestDurationMicroseconds = new client.Histogram({
  name: 'cloudstream_http_request_duration_seconds',
  help: 'Duration of HTTP requests in seconds',
  labelNames: ['method', 'route', 'code'],
  buckets: [0.01, 0.05, 0.1, 0.5, 1, 2, 4, 10],
});

export const discoveryOperationsTotal = new client.Counter({
  name: 'cloudstream_discovery_operations_total',
  help: 'Total discovery operations categorized by outcome',
  labelNames: ['outcome'], // 'cache_hit' | 'sync_success' | 'indexing_fallback' | 'empty'
});

export const register = client.register;
