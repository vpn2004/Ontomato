import { beforeEach, describe, expect, it, vi } from "vitest";

const transport = vi.hoisted(() => ({ frames: [] as unknown[] }));
vi.mock("../../../i18n", () => ({ tApp: (key: string) => key }));
vi.mock("../../../logging/logger", () => ({ createLogger: () => ({ info() {}, warn() {}, error() {} }) }));
vi.mock("../../../config/data-query-api", () => ({
  getApiConfig: () => ({ endpoints: { check: "/check" } }),
  buildHeaders: () => ({}), buildUrl: () => "http://test/check",
}));
vi.mock("../sse-transport", () => ({
  streamBackendSSE: async function* (_endpoint: string, _url: string, _init: unknown, opened: () => void) {
    opened();
    for (const frame of transport.frames) yield frame;
  },
}));
import { streamSelfCheckSSE } from "../quality-check";

describe("quality check completion contract", () => {
  beforeEach(() => { transport.frames = []; });

  it("accepts a complete negative assessment including a zero score", async () => {
    transport.frames = [{ label: "program-1", meaning: "Missing filter" }, { conclusion: "Wrong scope", score: 0, fittedQuestion: "All rows" }];
    const finish = vi.fn();
    const result = await streamSelfCheckSSE({ sessionId: "test", abcQuestionMode: "harness", onFinish: finish });
    expect(result?.score).toBe(0);
    expect(result?.steps).toHaveLength(1);
    expect(result?.steps[0]?.label).toBe("program-1");
    expect(finish).toHaveBeenCalledOnce();
  });

  it.each([null, "", "NaN", -1, 101, false])("rejects an invalid score %s", async (score) => {
    transport.frames = [{ conclusion: "Reason", score, fittedQuestion: "Scope" }];
    const finish = vi.fn();
    expect(await streamSelfCheckSSE({ sessionId: "test", abcQuestionMode: "harness", onFinish: finish })).toBeNull();
    expect(finish).not.toHaveBeenCalled();
  });

  it("does not accept an empty or progress-only stream as a finished review", async () => {
    const finish = vi.fn();
    for (const frames of [[], [{ meaning: "Still checking" }], [{ score: 95 }]]) {
      transport.frames = frames;
      expect(await streamSelfCheckSSE({ sessionId: "test", abcQuestionMode: "harness", onFinish: finish })).toBeNull();
    }
    expect(finish).not.toHaveBeenCalled();
  });

  it("reports backend errors instead of converting them into a successful empty result", async () => {
    transport.frames = [{ error: "Quality check failed" }];
    const error = vi.fn();
    expect(await streamSelfCheckSSE({ sessionId: "test", abcQuestionMode: "harness", onError: error })).toBeNull();
    expect(error).toHaveBeenCalledOnce();
  });
});
