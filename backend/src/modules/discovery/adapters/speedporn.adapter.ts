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
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
    'Accept':
      'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7',
    'Accept-Language': 'en-US,en;q=0.9',
    'Sec-CH-UA': '"Chromium";v="128", "Not;A=Brand";v="24", "Google Chrome";v="128"',
    'Sec-CH-UA-Mobile': '?0',
    'Sec-CH-UA-Platform': '"Windows"',
    'Sec-Fetch-Dest': 'document',
    'Sec-Fetch-Mode': 'navigate',
    'Sec-Fetch-Site': 'same-origin',
    'Sec-Fetch-User': '?1',
    'Upgrade-Insecure-Requests': '1',
  };

  async search(query: SearchQuery, timeoutMs: number): Promise<DiscoveredStream[]> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), timeoutMs);

    try {
      // 1. Direct slug probe (super fast: 1 request, ~300ms)
      const directSlugs = new Set<string>();
      for (const term of [query.title, query.cleanTitle, ...(query.aliases || [])]) {
        if (!term) continue;
        const slug = term.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
        if (slug.length > 2) {
          directSlugs.add(`${this.baseUrl}/${slug}/`);
          // also add v-prefix and non-v variants (e.g. tushy-raw-v19 <-> tushy-raw-19)
          const noV = slug.replace(/-v(\d+)/g, '-$1');
          const withV = slug.replace(/-(\d{1,4})/g, '-v$1');
          directSlugs.add(`${this.baseUrl}/${noV}/`);
          directSlugs.add(`${this.baseUrl}/${withV}/`);
        }
      }

      for (const directUrl of directSlugs) {
        try {
          const res = await fetch(directUrl, {
            headers: this.headers,
            signal: controller.signal,
          });
          if (res.ok) {
            const html = await res.text();
            const streams = this.extractStreamsFromHtml(html);
            if (streams.length > 0) {
              logger.info({ adapter: this.id, directUrl, streams: streams.length }, 'Found direct slug match on SpeedPorn');
              return streams;
            }
          }
        } catch {
          // continue to next slug or search fallback
        }
      }

      // 2. Search fallback
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

        // Rank candidates prioritizing exact volume/part numbers in slug
        const numMatch = term.match(/\b(\d+)\b/);
        const queryNum = numMatch ? numMatch[1] : null;

        const sortedCandidates = [...new Set(candidateHrefs)].sort((a, b) => {
          if (!queryNum) return 0;
          const aHas = a.toLowerCase().includes(`-${queryNum}/`) || a.toLowerCase().includes(`-v${queryNum}/`);
          const bHas = b.toLowerCase().includes(`-${queryNum}/`) || b.toLowerCase().includes(`-v${queryNum}/`);
          if (aHas && !bHas) return -1;
          if (!aHas && bHas) return 1;
          return 0;
        });

        const targetHrefs = sortedCandidates.slice(0, 3);

        for (const movieUrl of targetHrefs) {
          try {
            const mRes = await fetch(movieUrl, {
              headers: this.headers,
              signal: controller.signal,
            });
            if (!mRes.ok) continue;
            const mHtml = await mRes.text();
            const extracted = this.extractStreamsFromHtml(mHtml);
            if (extracted.length > 0) {
              streams.push(...extracted);
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

  private extractStreamsFromHtml(mHtml: string): DiscoveredStream[] {
    const streams: DiscoveredStream[] = [];

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
        logger.warn({ adapter: this.id, err: parseErr }, 'Failed parsing data-servers JSON');
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

    return streams;
  }

  async healthCheck(): Promise<boolean> {
    return true;
  }
}
