export interface DiscoveredStream {
  sourceId: string;
  sourceName: string;
  streamUrl: string;
  quality: '480p' | '720p' | '1080p' | '2160p' | 'unknown';
  isM3u8: boolean;
  sizeBytes?: number;
  headers?: Record<string, string>;
  confidenceScore: number; // 0.0 to 1.0 (matching accuracy)
}

export interface SearchQuery {
  tpdbId: string;
  contentType: 'MOVIE' | 'SERIES';
  title: string;
  aliases: string[];
  cleanTitle: string;
  releaseDate?: string;
  season?: number;
  episode?: number;
}

export interface AdapterLimits {
  maxConcurrentInFlight: number;
  rateLimitWindowMs: number;
  maxRequestsPerWindow: number;
  backoffOnHttp429Ms: number;
}

export interface ISourceAdapter {
  readonly id: string;
  readonly name: string;
  readonly isEnabled: boolean;
  readonly priority: number;
  readonly limits: AdapterLimits;

  search(query: SearchQuery, timeoutMs: number): Promise<DiscoveredStream[]>;
  healthCheck(): Promise<boolean>;
}
