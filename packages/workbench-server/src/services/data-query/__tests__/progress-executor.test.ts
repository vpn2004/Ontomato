import { afterEach, describe, expect, it, vi } from "vitest";
const io = vi.hoisted(() => ({ run: vi.fn() }));
vi.mock("../branches/abc-branch", () => ({ runAbcBranch: io.run }));
vi.mock("../branches/hot-branch", () => ({ runHotBranch: vi.fn() }));
vi.mock("../branches/static-branch", () => ({ runStaticBranch: vi.fn() }));
vi.mock("../data-query-policy", () => ({ getAbcQuestionMode: () => "harness", getQueryTimeoutMs: () => 600_000 }));
vi.mock("../../../config/data-query-api", () => ({ getApiConfig: () => ({ endpoints: {} }) }));
vi.mock("../../../logging/stage-timing", () => ({ measureStage: (_s: string, f: () => unknown) => Promise.resolve(f()), startStage: () => () => {} }));
vi.mock("../../../i18n", () => ({ t: (key: string) => key, tApp: (key: string) => key, tForLocale: (_l: string, key: string) => key }));
import { executeBaseQuery } from "../base-query-executor";
import type { AbcBranchContext, BaseQueryOutput } from "../branches/types";
afterEach(() => { vi.useRealTimers(); vi.clearAllMocks(); });
describe("query execution deadline", () => {
  it("actually delivers a productive branch's answer after the former ten-minute limit", async () => {
    vi.useFakeTimers();
    let ctx!: AbcBranchContext; let finish!: (result: BaseQueryOutput) => void;
    io.run.mockImplementation((context) => { ctx = context; return new Promise((resolve) => { finish = resolve; }); });
    const run = executeBaseQuery({ queryQuestion: "Long investigation", token: "", apiKey: "", userId: "user", enabledBranches: ["abc"] });
    await vi.advanceTimersByTimeAsync(500_000);
    ctx.reportProductiveProgress!("new-committed-evidence");
    await vi.advanceTimersByTimeAsync(400_000);
    expect(ctx.signal.aborted).toBe(false);
    finish({ content: "Full answer", datasets: [{ data: [{ count: 42 }] }] as any });
    const result = await run;
    expect(result.winner).toBe("abc");
    expect(result.output?.content).toBe("Full answer");
    expect(vi.getTimerCount()).toBe(0);
  });
});
