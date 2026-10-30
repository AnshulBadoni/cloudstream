import { describe, it, expect } from 'vitest';
import { LocalSingleflight } from './singleflight.js';

describe('LocalSingleflight', () => {
  it('coalesces multiple concurrent executions into exactly one invocation', async () => {
    const sf = new LocalSingleflight();
    let counter = 0;

    const fn = async () => {
      counter++;
      await new Promise((resolve) => setTimeout(resolve, 50));
      return `result_${counter}`;
    };

    // Run 50 concurrent calls
    const promises = Array.from({ length: 50 }, () => sf.do('test_key', fn));
    const results = await Promise.all(promises);

    expect(counter).toBe(1);
    expect(results.every((r) => r === 'result_1')).toBe(true);
  });

  it('allows subsequent calls after initial resolution has completed', async () => {
    const sf = new LocalSingleflight();
    let counter = 0;

    const fn = async () => {
      counter++;
      return counter;
    };

    const first = await sf.do('key2', fn);
    expect(first).toBe(1);

    const second = await sf.do('key2', fn);
    expect(second).toBe(2);
  });
});
