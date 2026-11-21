/** Collection has its own query deadline; it must not consume the report's reasoning allowance. */
export function createReportBudget(options = { reasoningMs: 1_200_000, totalMs: 3_600_000 }) {
  const abort = new AbortController();
  let remaining = options.reasoningMs;
  let started = performance.now();
  let reasoning: ReturnType<typeof setTimeout> | undefined;
  let disposed = false;
  let collecting = false;
  const expire = (kind: string) => abort.abort(new Error(`Report ${kind} budget exhausted; evidence was preserved.`));
  const resume = () => {
    if (disposed || abort.signal.aborted) return;
    started = performance.now();
    if (remaining <= 0) expire("reasoning");
    else reasoning = setTimeout(() => expire("reasoning"), remaining);
  };
  const total = setTimeout(() => expire("total execution"), options.totalMs);
  resume();
  return {
    signal: abort.signal,
    async collect<T>(run: () => Promise<T>, callerSignal?: AbortSignal): Promise<T> {
      const signal = callerSignal ? AbortSignal.any([abort.signal, callerSignal]) : abort.signal;
      signal.throwIfAborted();
      if (disposed || collecting) throw new Error("Report collection requires an active sequential execution.");
      clearTimeout(reasoning);
      remaining -= performance.now() - started;
      if (remaining <= 0) { expire("reasoning"); abort.signal.throwIfAborted(); }
      collecting = true;
      let onAbort = () => {};
      const cancelled = new Promise<never>((_resolve, reject) => {
        onAbort = () => reject(signal.reason);
        signal.addEventListener("abort", onAbort, { once: true });
        if (signal.aborted) onAbort();
      });
      try {
        const result = await Promise.race([Promise.resolve().then(() => { signal.throwIfAborted(); return run(); }), cancelled]);
        signal.throwIfAborted();
        return result;
      } finally { signal.removeEventListener("abort", onAbort); collecting = false; resume(); }
    },
    dispose() { disposed = true; clearTimeout(reasoning); clearTimeout(total); },
  };
}
