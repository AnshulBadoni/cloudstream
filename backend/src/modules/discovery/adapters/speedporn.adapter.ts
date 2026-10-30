import { ISourceAdapter, SearchQuery, DiscoveredStream, AdapterLimits } from './base.adapter.js';
import { logger } from '../../../utils/logger.js';

export class SpeedPornAdapter implements ISourceAdapter {
  readonly id = 'speedporn';
  readonly name = 'SpeedPorn';
  readonly isEnabled = true;
  readonly priority = 20; // High priority for full-length continuous movies
  readonly limits: AdapterLimits = {
    maxConcurrentInFlight: 5,
    rateLimitWindowMs: 1000,
    maxRequestsPerWindow: 5,
    backoffOnHttp429Ms: 30000,
  };

  private baseUrl = 'https://speedporn.net';
  private headers = {
    'Referer': 'https://speedporn.net/',
    'User-Agent':
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
  };

  async search(query: SearchQuery, timeoutMs: number): Promise<DiscoveredStream[]> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), timeoutMs);

    try {
      const searchTerms = [query.cleanTitle, ...(query.aliases || [])]
        .map((t) => t.trim())
        .filter((t) => t.length > 2);

      const uniqueTerms = Array.from(new Set(searchTerms));
      const streams: DiscoveredStream[] = [];

      for (const term of uniqueTerms.slice(0, 3)) {
        const encodedTerm = encodeURIComponent(term.replace(/[^\w\s]/g, ' ').replace(/\s+/g, ' ').trim());
        const searchUrl = `${this.baseUrl}/?s=${encodedTerm}`;

        logger.info({ adapter: this.id, term, searchUrl }, 'Searching SpeedPorn for full movies');
        const res = await fetch(searchUrl, {
          headers: this.headers,
          signal: controller.signal,
        });

        if (!res.ok) continue;
        const html = await res.text();

        // Extract movie candidate links: <a class="card__th" href="(https://speedporn.net/[^"]+/)">
        const candidateHrefs = [...html.matchAll(/<a[^>]+class=["'][^"']*card__th[^"']*["'][^>]*href=["'](https?:\/\/speedporn\.net\/[^"']+\/)["']/gi)]
          .map((m) => m[1])
          .filter((u) => !u.includes('/category/') && !u.includes('/tag/') && !u.includes('/author/') && !u.includes('/page/'));

        const targetHrefs = Array.from(new Set(candidateHrefs)).slice(0, 3);

        for (const movieUrl of targetHrefs) {
          try {
            const mRes = await fetch(movieUrl, {
              headers: this.headers,
              signal: controller.signal,
            });
            if (!mRes.ok) continue;
            const mHtml = await mRes.text();

            // Extract data-servers embeds (VOE, LuluStream, MixDrop, DoodStream, etc.)
            const serversMatch = mHtml.match(/data-servers=["']([^"']+)["']/i);
            if (serversMatch) {
              const rawJson = serversMatch[1].replace(/&quot;/g, '"').replace(/\\\//g, '/');
              try {
                const serverList = JSON.parse(rawJson);
                for (const item of serverList) {
                  if (item.u) {
                    streams.push({
                      sourceId: this.id,
                      sourceName: item.l ? `SpeedPorn (${item.l})` : 'SpeedPorn',
                      streamUrl: item.u,
                      quality: '1080p',
                      isM3u8: item.u.includes('.m3u8'),
                      headers: {
                        Referer: `${this.baseUrl}/`,
                      },
                      confidenceScore: 0.95,
                    });
                  }
                }
              } catch (parseErr) {
                logger.warn({ adapter: this.id, movieUrl, err: parseErr }, 'Failed parsing data-servers JSON');
              }
            }

            // Fallback: look for direct iframe embeds if data-servers was not found
            if (streams.length === 0) {
              const iframes = [...mHtml.matchAll(/<iframe[^>]+src=["'](https?:\/\/[^"']*(?:luluvdo|lulustream|streamtape|voe|mixdrop|playmogo|dood)[^"']*)["']/gi)]
                .map((m) => m[1].replace(/\\\//g, '/'));

              for (const iframeUrl of iframes) {
                streams.push({
                  sourceId: this.id,
                  sourceName: 'SpeedPorn',
                  streamUrl: iframeUrl,
                  quality: '1080p',
                  isM3u8: iframeUrl.includes('.m3u8'),
                  headers: {
                    Referer: `${this.baseUrl}/`,
                  },
                  confidenceScore: 0.9,
                });
              }
            }

            if (streams.length > 0) {
              break;
            }
          } catch (mErr: any) {
            logger.warn({ adapter: this.id, movieUrl, err: mErr.message }, 'Failed fetching candidate SpeedPorn post');
          }
        }

        if (streams.length > 0) break;
      }

      logger.info({ adapter: this.id, foundStreams: streams.length }, 'SpeedPorn discovery finished');
      return streams;
    } catch (err: any) {
      if (err.name === 'AbortError') {
        logger.warn({ adapter: this.id, timeoutMs }, 'SpeedPorn search timed out');
      } else {
        logger.error({ adapter: this.id, err }, 'SpeedPorn search failed');
      }
      return [];
    } finally {
      clearTimeout(timeout);
    }
  }

  async healthCheck(): Promise<boolean> {
    return true;
  }
}
