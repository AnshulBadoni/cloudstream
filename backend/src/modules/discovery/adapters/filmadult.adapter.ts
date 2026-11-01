import { ISourceAdapter, SearchQuery, DiscoveredStream, AdapterLimits } from './base.adapter.js';
import { logger } from '../../../utils/logger.js';

export class FilmAdultAdapter implements ISourceAdapter {
  readonly id = 'filmadult';
  readonly name = 'FilmAdult';
  readonly isEnabled = true;
  readonly priority = 15;
  readonly limits: AdapterLimits = {
    maxConcurrentInFlight: 5,
    rateLimitWindowMs: 1000,
    maxRequestsPerWindow: 5,
    backoffOnHttp429Ms: 30000,
  };

  private baseUrl = 'https://film-adult.video';
  private headers = {
    'Referer': 'https://film-adult.video/en/',
    'User-Agent':
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
    'Accept-Language': 'en-US,en;q=0.9',
  };

  async search(query: SearchQuery, timeoutMs: number): Promise<DiscoveredStream[]> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), timeoutMs);

    try {
      const searchTerms = [query.cleanTitle, query.title, ...(query.aliases || [])]
        .map((t) => t.trim())
        .filter((t) => t.length > 2);

      const uniqueTerms = Array.from(new Set(searchTerms));
      const streams: DiscoveredStream[] = [];

      for (const term of uniqueTerms.slice(0, 3)) {
        const encodedTerm = encodeURIComponent(term.replace(/[^\w\s]/g, ' ').replace(/\s+/g, ' ').trim());
        const searchUrl = `${this.baseUrl}/en/index.php?do=search&subaction=search&story=${encodedTerm}`;

        logger.info({ adapter: this.id, term, searchUrl }, 'Searching FilmAdult for full movies');
        const res = await fetch(searchUrl, {
          headers: this.headers,
          signal: controller.signal,
        });

        if (!res.ok) continue;
        const html = await res.text();

        // Extract movie candidate links: <a href="https://film-adult.video/en/168-pirates-2-stagnettis-revenge.html">
        const candidateHrefs = [...html.matchAll(/href=["'](https?:\/\/film-adult\.video\/(?:en\/)?(\d+-[^"']+\.html))["']/gi)]
          .map((m) => m[1]);

        const uniqueHrefs = Array.from(new Set(candidateHrefs)).slice(0, 3);

        for (const movieUrl of uniqueHrefs) {
          try {
            const mRes = await fetch(movieUrl, {
              headers: this.headers,
              signal: controller.signal,
            });
            if (!mRes.ok) continue;
            const mHtml = await mRes.text();
            const extracted = await this.extractStreamsFromHtml(mHtml, controller.signal);
            if (extracted.length > 0) {
              streams.push(...extracted);
              break;
            }
          } catch (mErr: any) {
            logger.warn({ adapter: this.id, movieUrl, err: mErr.message }, 'Failed fetching candidate FilmAdult post');
          }
        }

        if (streams.length > 0) break;
      }

      logger.info({ adapter: this.id, foundStreams: streams.length }, 'FilmAdult discovery finished');
      return streams;
    } catch (err: any) {
      if (err.name === 'AbortError') {
        logger.warn({ adapter: this.id, timeoutMs }, 'FilmAdult search timed out');
      } else {
        logger.error({ adapter: this.id, err }, 'FilmAdult search failed');
      }
      return [];
    } finally {
      clearTimeout(timeout);
    }
  }

  private async extractStreamsFromHtml(mHtml: string, signal: AbortSignal): Promise<DiscoveredStream[]> {
    const streams: DiscoveredStream[] = [];

    // 1. Extract embed links from dynamic script tags (e.g. s2.src = "https://...")
    const scriptEmbeds = [...mHtml.matchAll(/s2\.src\s*=\s*["']([^"']+)["']/gi)].map((m) => m[1]);
    const iframeEmbeds = [...mHtml.matchAll(/<iframe[^>]+src=["'](https?:\/\/[^"']+)["']/gi)].map((m) => m[1]);
    const allEmbeds = Array.from(new Set([...scriptEmbeds, ...iframeEmbeds]));

    for (const embedUrl of allEmbeds) {
      // If FilmCDN player: resolve direct m3u8
      if (embedUrl.includes('filmcdn.top')) {
        try {
          const fRes = await fetch(embedUrl, {
            headers: {
              ...this.headers,
              Referer: `${this.baseUrl}/`,
            },
            signal,
          });
          if (fRes.ok) {
            const fHtml = await fRes.text();
            const m3u8Match = fHtml.match(/https?:\/\/[^\s"'<>]+\.mp4\.m3u8[^\s"'<>]*/i) ||
                              fHtml.match(/https?:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*/i);
            if (m3u8Match) {
              streams.push({
                sourceId: this.id,
                sourceName: 'FilmAdult (FilmCDN)',
                streamUrl: m3u8Match[0],
                quality: '1080p',
                isM3u8: true,
                headers: {
                  Referer: embedUrl,
                  Origin: 'https://s2.filmcdn.top',
                },
                confidenceScore: 0.95,
              });
            }
          }
        } catch {}
      } else if (embedUrl.includes('hgcloud.to') || embedUrl.includes('streamwish') || embedUrl.includes('filelions')) {
        streams.push({
          sourceId: this.id,
          sourceName: 'FilmAdult (HGCloud)',
          streamUrl: embedUrl,
          quality: '1080p',
          isM3u8: false,
          headers: {
            Referer: `${this.baseUrl}/`,
          },
          confidenceScore: 0.9,
        });
      } else if (embedUrl.includes('playmogo') || embedUrl.includes('dood')) {
        streams.push({
          sourceId: this.id,
          sourceName: 'FilmAdult (Dood)',
          streamUrl: embedUrl,
          quality: '1080p',
          isM3u8: false,
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
