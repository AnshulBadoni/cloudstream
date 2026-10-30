import { ISourceAdapter, SearchQuery, DiscoveredStream, AdapterLimits } from './base.adapter.js';
import { logger } from '../../../utils/logger.js';

export class ParadiseHillAdapter implements ISourceAdapter {
  readonly id = 'paradisehill';
  readonly name = 'ParadiseHill';
  readonly isEnabled = true;
  readonly priority = 10;
  readonly limits: AdapterLimits = {
    maxConcurrentInFlight: 5,
    rateLimitWindowMs: 1000,
    maxRequestsPerWindow: 5,
    backoffOnHttp429Ms: 30000,
  };

  private baseUrl = 'https://en.paradisehill.cc';
  private headers = {
    'Referer': 'https://en.paradisehill.cc/',
    'User-Agent':
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
    'Cookie': 'is18=1; _csrf-frontend=1',
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
        const searchUrl = `${this.baseUrl}/search/?pattern=${encodedTerm}&what=1`;

        logger.info({ adapter: this.id, term, searchUrl }, 'Searching ParadiseHill');
        const res = await fetch(searchUrl, {
          headers: this.headers,
          signal: controller.signal,
        });

        if (!res.ok) continue;
        const html = await res.text();

        // Extract movie candidate links: <div class="item list-film-item" ...><a href="(/[^"]+/)">
        const linkMatches = [...html.matchAll(/<div[^>]*class=["'][^"']*list-film-item[^"']*["'][^>]*>[\s\S]*?<a[^>]*href=["'](\/[^"']+\/)["']/gi)]
          .map((m) => m[1])
          .filter((u) => !u.includes('/actor/') && !u.includes('/signup/') && !u.includes('/login/'));

        // Fallback: any item link if specific class wasn't found
        const candidateHrefs = linkMatches.length > 0 ? linkMatches : [...html.matchAll(/href=["'](\/[a-z0-9_]+\/)["']/gi)]
          .map((m) => m[1])
          .filter((u) => !u.includes('/actor/') && !u.includes('/search/') && !u.includes('/signup/') && !u.includes('/login/') && !u.includes('/category/'));

        const targetHrefs = Array.from(new Set(candidateHrefs)).slice(0, 3);

        for (const href of targetHrefs) {
          const movieUrl = `${this.baseUrl}${href}`;
          try {
            const mRes = await fetch(movieUrl, {
              headers: this.headers,
              signal: controller.signal,
            });
            if (!mRes.ok) continue;
            const mHtml = await mRes.text();

            // Extract MP4 sources from videoList or page regex
            const foundMp4s = new Set<string>();

            // Pattern 1: var videoList = [...]
            const videoListMatch = mHtml.match(/var\s+videoList\s*=\s*(\[[^;]+\]);/);
            if (videoListMatch) {
              const srcMatches = [...videoListMatch[1].matchAll(/["']src["']\s*:\s*["']([^"']+\.mp4[^"']*)["']/gi)];
              for (const sm of srcMatches) {
                foundMp4s.add(sm[1].replace(/\\\//g, '/'));
              }
            }

            // Pattern 2: direct regex match
            if (foundMp4s.size === 0) {
              const rawMp4Matches = [...mHtml.matchAll(/https?:\\\/\\\/[^\s"'<>]+\.mp4[^\s"'<>]*/gi)];
              for (const rm of rawMp4Matches) {
                foundMp4s.add(rm[0].replace(/\\\//g, '/'));
              }
            }

            for (const mp4Url of foundMp4s) {
              streams.push({
                sourceId: this.id,
                sourceName: this.name,
                streamUrl: mp4Url,
                quality: '1080p',
                isM3u8: false,
                headers: {
                  Referer: `${this.baseUrl}/`,
                },
                confidenceScore: 0.95,
              });
            }

            if (streams.length > 0) {
              // Found valid streams, no need to crawl more candidates
              break;
            }
          } catch (mErr: any) {
            logger.warn({ adapter: this.id, movieUrl, err: mErr.message }, 'Failed fetching candidate movie');
          }
        }

        if (streams.length > 0) break;
      }

      logger.info({ adapter: this.id, foundStreams: streams.length }, 'ParadiseHill discovery finished');
      return streams;
    } catch (err: any) {
      if (err.name === 'AbortError') {
        logger.warn({ adapter: this.id, timeoutMs }, 'ParadiseHill search timed out');
      } else {
        logger.error({ adapter: this.id, err }, 'ParadiseHill search failed');
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
