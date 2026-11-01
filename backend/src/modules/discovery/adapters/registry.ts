import { ISourceAdapter } from './base.adapter.js';
import { SpeedPornAdapter } from './speedporn.adapter.js';
import { ParadiseHillAdapter } from './paradisehill.adapter.js';
import { PornoTorrentAdapter } from './pornotorrent.adapter.js';

export class AdapterRegistry {
  private adapters: Map<string, ISourceAdapter> = new Map();

  constructor() {
    this.register(new ParadiseHillAdapter());
    this.register(new SpeedPornAdapter());
    this.register(new PornoTorrentAdapter());
  }

  register(adapter: ISourceAdapter): void {
    this.adapters.set(adapter.id, adapter);
  }

  getEnabled(): ISourceAdapter[] {
    return Array.from(this.adapters.values())
      .filter((a) => a.isEnabled)
      .sort((a, b) => b.priority - a.priority);
  }

  get(id: string): ISourceAdapter | undefined {
    return this.adapters.get(id);
  }
}

export const adapterRegistry = new AdapterRegistry();
