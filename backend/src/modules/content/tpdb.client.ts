import { env } from '../../config/env.js';
import { logger } from '../../utils/logger.js';

export interface TpdbMetadata {
  tpdbId: string;
  title: string;
  aliases: string[];
  cleanTitle: string;
  studio?: string;
  releaseDate?: string;
  durationSeconds?: number;
  performers: string[];
  poster?: string;
  background?: string;
  description?: string;
}

export class TpdbClient {
  private apiKey: string;
  private baseUrl = 'https://api.theporndb.net';

  constructor(apiKey: string = env.TPDB_API_KEY) {
    this.apiKey = apiKey;
  }

  async getScene(id: string): Promise<TpdbMetadata | null> {
    try {
      const headers: Record<string, string> = {
        Accept: 'application/json',
      };
      if (this.apiKey) {
        headers['Authorization'] = `Bearer ${this.apiKey}`;
      }

      // Try /scenes/:id first, fallback to /movies/:id if 404 or missing
      let res = await fetch(`${this.baseUrl}/scenes/${encodeURIComponent(id)}`, { headers });
      if (!res.ok && res.status === 404) {
        res = await fetch(`${this.baseUrl}/movies/${encodeURIComponent(id)}`, { headers });
      }

      if (!res.ok) {
        if (res.status === 404) return null;
        logger.warn({ status: res.status, id }, 'TPDB API non-200 response');
        return null;
      }

      const json: any = await res.json();
      const data = json.data;
      if (!data) return null;

      const title = data.title || '';
      const cleanTitle = title
        .replace(/[-_.:]/g, ' ')
        .replace(/\s+/g, ' ')
        .trim();

      const aliases: string[] = [];
      if (data.site?.name) {
        aliases.push(`${data.site.name} ${cleanTitle}`);
      }

      return {
        tpdbId: id,
        title,
        aliases,
        cleanTitle,
        studio: data.site?.name,
        releaseDate: data.date,
        durationSeconds: data.duration,
        performers: (data.performers || []).map((p: any) => p.name),
        poster: data.poster,
        background: data.background?.large || data.background?.full,
        description: data.description,
      };
    } catch (err) {
      logger.error({ err, id }, 'Failed querying TPDB API');
      return null;
    }
  }
}

export const tpdbClient = new TpdbClient();
