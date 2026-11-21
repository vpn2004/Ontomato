/** Bounded preparation with progressive, ordered publication and shared cancellation. */
export class OrderedPreparation {
  private readonly controller = new AbortController();
  readonly signal: AbortSignal;
  private readonly pending: Promise<void>[] = [];
  private tail: Promise<void> = Promise.resolve();

  constructor(signal: AbortSignal, private readonly concurrency = 2) {
    if (!Number.isInteger(concurrency) || concurrency < 1) throw new Error("Invalid concurrency");
    this.signal = AbortSignal.any([signal, this.controller.signal]);
  }

  async add<T>(prepare: () => Promise<T>, publish: (value: T) => void): Promise<void> {
    // Bound unpublished results too, so a slow first table cannot accumulate a large buffer.
    while (this.pending.length >= this.concurrency) await this.wait(this.pending[0]!);
    this.signal.throwIfAborted();
    const prepared = Promise.resolve().then(prepare);
    // Observe a preparation failure immediately, even when an earlier table is still pending.
    void prepared.catch(error => this.controller.abort(error));
    const job = Promise.all([this.tail, prepared]).then(([, result]) => {
      this.signal.throwIfAborted();
      publish(result);
    });
    const settled = job.catch(error => { this.controller.abort(error); }).finally(() => {
      const index = this.pending.indexOf(settled);
      if (index >= 0) this.pending.splice(index, 1);
    });
    this.pending.push(settled);
    this.tail = settled;
  }

  async finish(): Promise<void> {
    await this.wait(Promise.all(this.pending));
    this.signal.throwIfAborted();
  }

  private async wait(promise: Promise<unknown>): Promise<void> {
    this.signal.throwIfAborted();
    let onAbort!: () => void;
    const aborted = new Promise<never>((_, reject) => {
      onAbort = () => reject(this.signal.reason);
      this.signal.addEventListener("abort", onAbort, { once: true });
    });
    try { await Promise.race([promise, aborted]); }
    finally { this.signal.removeEventListener("abort", onAbort); }
  }

  stop(reason?: unknown): void { this.controller.abort(reason); }
}
