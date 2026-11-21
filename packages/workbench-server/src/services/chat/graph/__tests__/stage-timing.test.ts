import { afterEach, describe, expect, it, vi } from "vitest";
import { StateGraph, START, END } from "@langchain/langgraph";
import { GraphStateAnnotation, type GraphState } from "../state";
import { timedNode } from "../utils/node-timing";
import { measureStage, startStage, type StageTiming } from "../../../../logging/stage-timing";

const logs = vi.hoisted(() => ({ info: vi.fn() }));
vi.mock("../../../../logging/logger", () => ({ createLogger: () => logs }));
afterEach(() => { vi.restoreAllMocks(); logs.info.mockClear(); });

const state = {} as GraphState;
describe("request-scoped stage timing", () => {
  it("measures actual graph execution before the streamed update arrives", async () => {
    let now = 0;
    vi.spyOn(performance, "now").mockImplementation(() => now);
    const timings: StageTiming[] = [];
    const graph = new StateGraph(GraphStateAnnotation)
      .addNode("query", timedNode("query", async () => {
        now += 1234;
        return { errors: [] };
      }, (_, t) => timings.push(t)))
      .addEdge(START, "query").addEdge("query", END).compile();
    let updates = 0;
    for await (const _event of await graph.stream({ userQuestion: "test" })) {
      updates++;
      expect(timings[0].durationMs).toBe(1234);
    }
    expect(updates).toBeGreaterThan(0);
    expect(timings[0].outcome).toBe("completed");
  });

  it("keeps concurrent collectors and output identities isolated", async () => {
    let release!: () => void;
    const first: StageTiming[] = [], second: StageTiming[] = [];
    const output = { errors: [] };
    const slow = timedNode("query", async () => {
      await new Promise<void>((r) => { release = r; });
      return output;
    }, (_, t) => first.push(t));
    const pending = slow(state);
    await timedNode("query", async () => ({ errors: [{ node: "query", message: "failure", timestamp: 0 }] }), (_, t) => second.push(t))(state);
    expect(first).toEqual([]);
    expect(second[0].outcome).toBe("failed");
    release();
    expect(await pending).toBe(output);
    expect(first[0].outcome).toBe("completed");
  });

  it("preserves the original exception and distinguishes cancellation", async () => {
    const original = new Error("private backend detail");
    const abort = new AbortController();
    const timings: StageTiming[] = [];
    const node = timedNode("query", async () => { abort.abort(); throw original; }, (_, t) => timings.push(t));
    await expect(node(state, { configurable: { signal: abort.signal } })).rejects.toBe(original);
    expect(timings[0].outcome).toBe("cancelled");
    expect(JSON.stringify(logs.info.mock.calls)).not.toContain(original.message);
    await expect(measureStage("failed", async () => { throw original; })).rejects.toBe(original);
    expect(logs.info.mock.calls.at(-1)?.[1].outcome).toBe("failed");
  });

  it("uses monotonic elapsed time despite wall-clock changes and finishes once", () => {
    let clock = 100;
    vi.spyOn(performance, "now").mockImplementation(() => clock);
    vi.spyOn(Date, "now").mockReturnValueOnce(5000).mockReturnValue(1000);
    const finish = startStage("test");
    clock += 700;
    const timing = finish();
    expect(timing.durationMs).toBe(700);
    expect(timing.startedAt).toBe(5000);
    expect(finish("failed")).toBe(timing);
    expect(logs.info).toHaveBeenCalledTimes(1);
  });

  it("does not let an observation callback replace results or errors", async () => {
    const onFinish = () => { throw new Error("observer failed"); };
    expect(await measureStage("test", async () => 42, { onFinish })).toBe(42);
    const error = new Error("original");
    await expect(measureStage("test", async () => { throw error; }, { onFinish })).rejects.toBe(error);
  });
});
