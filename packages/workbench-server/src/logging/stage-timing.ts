import { createLogger } from "./logger";

const logger = createLogger("query-timing");
export type StageOutcome = "completed" | "failed" | "cancelled" | "empty" | "fallback";
export interface StageTiming {
  stage: string;
  startedAt: number;
  endedAt: number;
  durationMs: number;
  outcome: StageOutcome;
}

// Wall-clock timestamps support persistence; a monotonic clock measures elapsed time.
export function startStage(stage: string, metadata: Record<string, unknown> = {}) {
  const startedAt = Date.now();
  const started = performance.now();
  let result: StageTiming | undefined;
  return (outcome: StageOutcome = "completed", details: Record<string, unknown> = {}) => {
    if (result) return result;
    result = {
      stage, startedAt, endedAt: Date.now(),
      durationMs: Math.max(0, Math.round(performance.now() - started)), outcome,
    };
    logger.info("query stage completed", { ...metadata, ...details, ...result });
    return result;
  };
}

export async function measureStage<T>(
  stage: string,
  operation: () => Promise<T>,
  options: {
    signal?: AbortSignal;
    metadata?: Record<string, unknown>;
    classify?: (value: T) => StageOutcome;
    onFinish?: (timing: StageTiming) => void;
  } = {}
): Promise<T> {
  const finish = startStage(stage, options.metadata);
  let outcome: StageOutcome = "failed";
  try {
    const value = await operation();
    outcome = options.signal?.aborted ? "cancelled" : options.classify?.(value) ?? "completed";
    return value;
  } catch (error) {
    if (options.signal?.aborted || (error instanceof Error && error.name === "AbortError")) {
      outcome = "cancelled";
    }
    throw error;
  } finally {
    const timing = finish(outcome);
    // An observation callback must never replace a result or the original exception.
    try { options.onFinish?.(timing); } catch { /* Observation only. */ }
  }
}
