export class CircuitBreaker {
  private failureCount = new Map<string, number>();
  private lastFailureTime = new Map<string, number>();
  private state = new Map<string, 'CLOSED' | 'OPEN' | 'HALF_OPEN'>();

  constructor(
    private threshold: number = 5,
    private cooldownMs: number = 60000
  ) {}

  isOpen(id: string): boolean {
    const currentState = this.state.get(id) || 'CLOSED';
    if (currentState === 'CLOSED') return false;

    const lastFailure = this.lastFailureTime.get(id) || 0;
    if (Date.now() - lastFailure > this.cooldownMs) {
      this.state.set(id, 'HALF_OPEN');
      return false; // Allow a probe
    }

    return true;
  }

  recordSuccess(id: string): void {
    this.failureCount.set(id, 0);
    this.state.set(id, 'CLOSED');
  }

  recordFailure(id: string): void {
    const count = (this.failureCount.get(id) || 0) + 1;
    this.failureCount.set(id, count);
    this.lastFailureTime.set(id, Date.now());

    if (count >= this.threshold) {
      this.state.set(id, 'OPEN');
    }
  }
}

export const circuitBreaker = new CircuitBreaker();
