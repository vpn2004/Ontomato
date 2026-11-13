import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("../../../i18n", () => ({ tApp: (key: string) => key }));
vi.mock("../../../logging/logger", () => ({ createLogger: () => ({ info() {}, warn() {}, error() {} }) }));
vi.mock("../qc-state", () => ({ buildQcMarkdown: () => "QC" }));
vi.mock("../query-run-store", () => ({ updateQueryRunQualityCheck: vi.fn().mockResolvedValue(1) }));
vi.mock("../quality-check", () => ({ streamSelfCheckSSE: vi.fn() }));
import { streamSelfCheckSSE } from "../quality-check";
import { updateQueryRunQualityCheck } from "../query-run-store";
import { LATE_FACT_SETTLE_TIMEOUT_MS, startQualityCheckSettlement } from "../late-fact";

const input = {
  identity: { threadId: "thread", requestSeq: 0, sourceKind: "chat", sourceRef: "test" },
  sessionId: "session", abcQuestionMode: "harness" as const,
};

describe("quality check terminal state persistence", () => {
  beforeEach(() => { vi.useFakeTimers(); vi.clearAllMocks(); });
  afterEach(() => { vi.useRealTimers(); });

  it("persists a completed assessment", async () => {
    const result = { conclusion: "Mismatch", score: 20, fittedQuestion: "Actual scope", steps: [] };
    vi.mocked(streamSelfCheckSSE).mockImplementation(async (options) => { options.onFinish?.(result); return result; });
    await startQualityCheckSettlement(input);
    expect(updateQueryRunQualityCheck).toHaveBeenCalledWith(expect.objectContaining({ qcResult: result, qcState: expect.objectContaining({ status: "completed" }) }));
  });

  it("persists a timeout without fabricating an assessment", async () => {
    vi.mocked(streamSelfCheckSSE).mockImplementation((options) => new Promise((resolve) => {
      options.onStep?.({ meaning: "Partial explanation" });
      options.signal!.addEventListener("abort", () => resolve(null), { once: true });
    }));
    const publish = vi.fn();
    const pending = startQualityCheckSettlement({ ...input, onState: publish });
    await vi.advanceTimersByTimeAsync(LATE_FACT_SETTLE_TIMEOUT_MS + 1);
    await pending;
    expect(publish).toHaveBeenLastCalledWith(expect.objectContaining({ status: "unsettled" }), "QC");
    expect(updateQueryRunQualityCheck).toHaveBeenCalledWith(expect.objectContaining({ qcResult: undefined, qcState: expect.objectContaining({ status: "unsettled", steps: [expect.objectContaining({ meaning: "Partial explanation" })] }) }));
  });

  it("persists failure when the stream has no final assessment", async () => {
    vi.mocked(streamSelfCheckSSE).mockResolvedValue(null);
    await startQualityCheckSettlement(input);
    expect(updateQueryRunQualityCheck).toHaveBeenCalledWith(expect.objectContaining({ qcResult: undefined, qcState: expect.objectContaining({ status: "failed" }) }));
  });

  it("lets a long multi-program review finish while distinct explanations keep arriving", async () => {
    const result = { conclusion: "All scopes agree", score: 95, fittedQuestion: "Complex query", steps: [] };
    vi.mocked(streamSelfCheckSSE).mockImplementation((options) => new Promise((resolve) => {
      options.signal!.addEventListener("abort", () => resolve(null), { once: true });
      for (let i = 1; i <= 4; i++) setTimeout(() => options.onStep?.({ meaning: `Verified program ${i}` }), i * 45_000);
      setTimeout(() => { options.onFinish?.(result); resolve(result); }, 220_000);
    }));
    const pending = startQualityCheckSettlement(input);
    await vi.advanceTimersByTimeAsync(220_000);
    await pending;
    expect(updateQueryRunQualityCheck).toHaveBeenCalledTimes(1);
    expect(updateQueryRunQualityCheck).toHaveBeenCalledWith(expect.objectContaining({
      qcResult: result, qcState: expect.objectContaining({ status: "completed" }),
    }));
    expect(vi.getTimerCount()).toBe(0);
  });

  it("repeated copies of the same explanation do not keep a stuck review alive", async () => {
    vi.mocked(streamSelfCheckSSE).mockImplementation((options) => new Promise((resolve) => {
      options.onStep?.({ meaning: "Unchanged" });
      setTimeout(() => options.onStep?.({ meaning: "Unchanged" }), 40_000);
      options.signal!.addEventListener("abort", () => resolve(null), { once: true });
    }));
    const pending = startQualityCheckSettlement(input);
    await vi.advanceTimersByTimeAsync(LATE_FACT_SETTLE_TIMEOUT_MS + 1);
    await pending;
    expect(updateQueryRunQualityCheck).toHaveBeenCalledWith(expect.objectContaining({
      qcState: expect.objectContaining({ status: "unsettled" }),
    }));
  });

  it("preserves caller cancellation without overwriting the run", async () => {
    vi.mocked(streamSelfCheckSSE).mockImplementation((options) => new Promise((resolve) => {
      options.signal!.addEventListener("abort", () => resolve(null), { once: true });
    }));
    const controller = new AbortController();
    const pending = startQualityCheckSettlement({ ...input, signal: controller.signal });
    controller.abort();
    await pending;
    expect(updateQueryRunQualityCheck).not.toHaveBeenCalled();
  });
});
