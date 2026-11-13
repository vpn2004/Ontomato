import { beforeEach, describe, expect, it, vi } from "vitest";
import { runAbcHarnessBranch } from "../branches/abc-harness-branch";
import type { AbcBranchContext } from "../branches/types";

const mocks = vi.hoisted(() => ({ prepare: vi.fn(), events: [] as any[], afterEvents: undefined as (() => Promise<void>) | undefined }));
vi.mock("../../../config/data-query-api", () => ({ getApiConfig: () => ({}), buildHeaders: () => ({}), buildUrl: () => "http://test" }));
vi.mock("../../../utils/backend-client", () => ({ getCircuitStatus: () => ({ isOpen: false }), AuthFailedError: class extends Error {}, BackendUnavailableError: class extends Error {} }));
vi.mock("../../../i18n", () => ({ getConclusionPrefix: () => "Conclusion", t: (key: string) => key, tForLocale: (_: string, key: string) => key, tApp: (key: string) => key }));
vi.mock("../adapter", () => ({ buildTableFromRows: mocks.prepare, normalizeAnswerRows: (rows: unknown) => rows }));
vi.mock("../sse-transport", () => ({ extractBackendLocation: () => undefined, streamBackendSSE: async function* () { yield* mocks.events; await mocks.afterEvents?.(); } }));
vi.mock("../branches/abc-harness-answer", () => ({ polishHarnessAnswer: async (input: any) => input.fallbackContent }));

function deferred<T>() { let resolve!: (value: T) => void; let reject!: (reason: Error) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
const table = (id: number) => ({ tableSegment: `table-${id}`, normalizedRows: [{ id }], fieldDisplayPlan: {} });
const event = (id: number) => ({ type: "DATA_TYPE", content: { question: `Question ${id}`, code: `code-${id}`, data: [{ id }], outKeyRefs: [] } });
function context(controller = new AbortController()) {
  return { queryQuestion: "test", token: "", userId: "test", apiKey: "", signal: controller.signal, pushEvent: vi.fn(), pushContent: vi.fn(), reportFallback: vi.fn(), reportBackendLocation: vi.fn(), setAbcThinking: vi.fn(), thinking: { ensureParallelStarted: vi.fn(), set: vi.fn(), log: vi.fn(), setContent: vi.fn() } } as unknown as AbcBranchContext;
}
beforeEach(() => { vi.clearAllMocks(); mocks.events = []; mocks.afterEvents = undefined; });
describe("Harness result preparation", () => {
  it("prepares two tables concurrently, bounds outstanding work, and publishes in input order", async () => {
    const gates = [deferred<ReturnType<typeof table>>(), deferred<ReturnType<typeof table>>(), deferred<ReturnType<typeof table>>()];
    mocks.events = [event(0), event(1), event(2)];
    mocks.prepare.mockImplementation((rows: Array<{id: number}>) => gates[rows[0]!.id]!.promise);
    const ctx = context(); const run = runAbcHarnessBranch(ctx);
    try {
      await vi.waitFor(() => expect(mocks.prepare).toHaveBeenCalledTimes(2), { timeout: 500 });
      gates[1]!.resolve(table(1));
      await new Promise(resolve => setTimeout(resolve, 20));
      expect(ctx.pushEvent).not.toHaveBeenCalledWith(expect.objectContaining({ type: "query_datasets" }));
      expect(mocks.prepare).toHaveBeenCalledTimes(2);
      gates[0]!.resolve(table(0));
      await vi.waitFor(() => expect(mocks.prepare).toHaveBeenCalledTimes(3));
      gates[2]!.resolve(table(2));
      const result = await run;
      expect(result?.abcCodes).toEqual(["code-0", "code-1", "code-2"]);
      expect(result?.datasets.map(d => d.data)).toEqual([[{ id: 0 }], [{ id: 1 }], [{ id: 2 }]]);
    } finally { gates.forEach((g, i) => g.resolve(table(i))); await run; }
  });
  it("publishes the first ready table while the backend stream is still open", async () => {
    const streamEnd = deferred<void>(); mocks.events = [event(0)]; mocks.afterEvents = () => streamEnd.promise;
    mocks.prepare.mockResolvedValue(table(0)); const ctx = context(); const run = runAbcHarnessBranch(ctx);
    try { await vi.waitFor(() => expect(ctx.pushEvent).toHaveBeenCalledWith(expect.objectContaining({ type: "query_datasets" }))); }
    finally { streamEnd.resolve(); await run; }
  });
  it("does not publish queued results after cancellation", async () => {
    const gate = deferred<ReturnType<typeof table>>(); mocks.events = [event(0), event(1)]; mocks.prepare.mockReturnValue(gate.promise);
    const controller = new AbortController(); const ctx = context(controller); const run = runAbcHarnessBranch(ctx); const rejected = expect(run).rejects.toThrow();
    await vi.waitFor(() => expect(mocks.prepare).toHaveBeenCalled()); controller.abort(); gate.resolve(table(0)); await rejected;
    expect(ctx.pushEvent).not.toHaveBeenCalledWith(expect.objectContaining({ type: "query_datasets" }));
  });
  it("stops pending publication on a stream failure and preserves already published data", async () => {
    const firstSent = deferred<void>(); const second = deferred<ReturnType<typeof table>>();
    mocks.events = [event(0), event(1)]; mocks.prepare.mockImplementation((rows: Array<{id:number}>) => rows[0]!.id === 0 ? table(0) : second.promise);
    mocks.afterEvents = async () => { await firstSent.promise; throw new Error("backend failed"); };
    const ctx = context(); vi.mocked(ctx.pushEvent).mockImplementation(e => { if (e.type === "query_datasets") firstSent.resolve(); });
    const run = runAbcHarnessBranch(ctx); const rejected = expect(run).rejects.toThrow("backend failed");
    await vi.waitFor(() => expect(ctx.pushEvent).toHaveBeenCalledWith(expect.objectContaining({ type: "query_datasets" }))); 
    second.resolve(table(1)); await rejected;
    expect(vi.mocked(ctx.pushEvent).mock.calls.filter(([e]) => e.type === "query_datasets").at(-1)?.[0].datasets).toHaveLength(1);
    expect(ctx.reportFallback).toHaveBeenLastCalledWith(expect.objectContaining({ datasets: expect.arrayContaining([expect.objectContaining({ data: [{id:0}] })]), errors: ["backend failed"] }));
  });
  it("returns promptly on cancellation even if preparation ignores its signal", async () => {
    mocks.events = [event(0)]; mocks.prepare.mockReturnValue(new Promise(() => {}));
    const controller = new AbortController(); const ctx = context(controller);
    let done = false; const run = runAbcHarnessBranch(ctx).catch(() => { done = true; });
    await vi.waitFor(() => expect(mocks.prepare).toHaveBeenCalled()); controller.abort();
    await vi.waitFor(() => expect(done).toBe(true), { timeout: 300 }); await run;
    expect(ctx.pushEvent).not.toHaveBeenCalledWith(expect.objectContaining({ type: "query_datasets" }));
  });

  it("propagates a preparation failure while an earlier preparation is still pending", async () => {
    mocks.events = [event(0), event(1), event(2)];
    mocks.prepare.mockImplementation((rows: Array<{id:number}>) => rows[0]!.id === 0 ? new Promise(() => {}) : Promise.reject(new Error("prepare failed")));
    const ctx = context();
    await expect(runAbcHarnessBranch(ctx)).rejects.toThrow("prepare failed");
    expect(ctx.pushEvent).not.toHaveBeenCalledWith(expect.objectContaining({ type: "query_datasets" }));
  });

});
