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
