import { ISourceAdapter, SearchQuery, DiscoveredStream, AdapterLimits } from './base.adapter.js';
import { logger } from '../../../utils/logger.js';

export class PornoTorrentAdapter implements ISourceAdapter {
  readonly id = 'pornotorrent';
  readonly name = 'PornoTorrent';
  readonly isEnabled = true;
  readonly priority = 10;
  readonly limits: AdapterLimits = {
    maxConcurrentInFlight: 5,
    rateLimitWindowMs: 1000,
    maxRequestsPerWindow: 5,
    backoffOnHttp429Ms: 30000,
  };

  private baseUrl = 'https://pornotorrent.com.br';
  private headers = {
    'Referer': 'https://pornotorrent.com.br/',
    'User-Agent':
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
    'Accept-Language': 'en-US,en;q=0.9',
  };

  async search(query: SearchQuery, timeoutMs: number): Promise<DiscoveredStream[]> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), timeoutMs);

    try {
      // 1. Direct slug probe
      const directSlugs = new Set<string>();
      const terms = [query.title, query.cleanTitle, ...(query.aliases || [])].filter(Boolean);

      for (const term of terms) {
        const slug = term
          .toLowerCase()
          .replace(/[–—]/g, '-')
          .replace(/[^a-z0-9]+/g, '-')
          .replace(/^-+|-+$/g, '');
        if (slug.length > 2) {
          directSlugs.add(`${this.baseUrl}/${slug}/`);
          directSlugs.add(`${this.baseUrl}/en/${slug}/`);
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
              logger.info({ adapter: this.id, directUrl, streams: streams.length }, 'Found direct slug match on PornoTorrent');
              return streams;
            }
          }
        } catch {
          // continue
        }
      }

      // 2. Search fallback
      const searchTerms = [query.cleanTitle, query.title, ...(query.aliases || [])]
        .map((t) => t.trim())
        .filter((t) => t.length > 2);

      const uniqueTerms = Array.from(new Set(searchTerms));
      const streams: DiscoveredStream[] = [];

      for (const term of uniqueTerms.slice(0, 3)) {
        const cleanSearch = term.replace(/[–—]/g, ' ').replace(/[^\w\s]/g, ' ').replace(/\s+/g, ' ').trim();
        const searchUrl = `${this.baseUrl}/?s=${encodeURIComponent(cleanSearch)}`;

        logger.info({ adapter: this.id, term: cleanSearch, searchUrl }, 'Searching PornoTorrent for torrent releases');
        const res = await fetch(searchUrl, {
          headers: this.headers,
          signal: controller.signal,
        });

        if (!res.ok) continue;
        const html = await res.text();

        // Extract movie candidates: <a class="cp-card__link" href="..." aria-label="...">
        const candidateRegex = /<a[^>]+class=["'][^"']*cp-card__link[^"']*["'][^>]*href=["'](https?:\/\/pornotorrent\.com\.br\/[^"']+)["'][^>]*aria-label=["']([^"']+)["']/gi;
        const candidates: { url: string; label: string }[] = [];
        let match;
        while ((match = candidateRegex.exec(html)) !== null) {
          const u = match[1];
          if (!u.includes('/category/') && !u.includes('/tag/') && !u.includes('/author/') && !u.includes('/minha-lista/')) {
            candidates.push({ url: u, label: match[2].trim() });
          }
        }

        // Fallback card links
        if (candidates.length === 0) {
          const fallbackRegex = /<a[^>]+class=["'][^"']*cp-card__link[^"']*["'][^>]*href=["'](https?:\/\/pornotorrent\.com\.br\/[^"']+)["']/gi;
          while ((match = fallbackRegex.exec(html)) !== null) {
            const u = match[1];
            if (!u.includes('/category/') && !u.includes('/tag/') && !u.includes('/author/') && !u.includes('/minha-lista/')) {
              candidates.push({ url: u, label: '' });
            }
          }
        }

        for (const candidate of candidates.slice(0, 3)) {
          try {
            const mRes = await fetch(candidate.url, {
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
            logger.warn({ adapter: this.id, candidateUrl: candidate.url, err: mErr.message }, 'Failed fetching PornoTorrent post');
          }
        }

        if (streams.length > 0) break;
      }

      logger.info({ adapter: this.id, foundStreams: streams.length }, 'PornoTorrent discovery finished');
      return streams;
    } catch (err: any) {
      if (err.name === 'AbortError') {
        logger.warn({ adapter: this.id, timeoutMs }, 'PornoTorrent search timed out');
      } else {
        logger.error({ adapter: this.id, err }, 'PornoTorrent search failed');
      }
      return [];
    } finally {
      clearTimeout(timeout);
    }
  }

  private extractStreamsFromHtml(html: string): DiscoveredStream[] {
    const streams: DiscoveredStream[] = [];
    const seenUrls = new Set<string>();

    // 1. Download button wrapper: /download/?m=magnet%3A...
    const downloadMatches = [...html.matchAll(/href=["']([^"']*(?:magnet|\/download\/\?m=)[^"']*)["']/gi)];
    for (const dm of downloadMatches) {
      const rawHref = dm[1];
      let magnetUrl = '';
      if (rawHref.includes('/download/?m=')) {
        magnetUrl = decodeURIComponent(rawHref.split('/download/?m=')[1]);
      } else if (rawHref.startsWith('magnet:')) {
        magnetUrl = rawHref;
      }

      if (magnetUrl.startsWith('magnet:') && !seenUrls.has(magnetUrl)) {
        seenUrls.add(magnetUrl);
        const quality = this.parseQuality(magnetUrl + ' ' + html);
        streams.push({
          sourceId: 'pornotorrent',
          sourceName: 'PornoTorrent',
          streamUrl: magnetUrl,
          quality,
          isM3u8: false,
          headers: {},
          confidenceScore: 0.95,
        });
      }
    }

    return streams;
  }

  private parseQuality(text: string): '480p' | '720p' | '1080p' | '2160p' | 'unknown' {
    const lower = text.toLowerCase();
    if (lower.includes('2160p') || lower.includes('4k') || lower.includes('uhd')) return '2160p';
    if (lower.includes('1080p') || lower.includes('1080')) return '1080p';
    if (lower.includes('720p') || lower.includes('720')) return '720p';
    if (lower.includes('480p') || lower.includes('480')) return '480p';
    return '1080p';
  }

  async healthCheck(): Promise<boolean> {
    try {
      const res = await fetch(this.baseUrl, {
        headers: this.headers,
        signal: AbortSignal.timeout(5000),
      });
      return res.ok;
    } catch {
      return false;
    }
  }
}
