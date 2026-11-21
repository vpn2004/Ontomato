import { beforeEach, describe, expect, it, vi } from "vitest";

const store = vi.hoisted(() => ({ run: undefined as any, verify: vi.fn() }));
vi.mock("../../../../data-query/query-run-store", () => ({
  getQueryRunBySource: vi.fn(async () => store.run),
  buildQueryRunId: () => "query-1",
}));
vi.mock("../../../../../infrastructure/connection", () => ({
  getCheckpointer: () => ({ verifyThreadAccess: store.verify }),
}));
vi.mock("../../../../../i18n", () => ({ tApp: (key: string) => key }));
vi.mock("../../../../../logging/logger", () => ({ createLogger: () => ({ warn() {}, error() {} }) }));
import * as cache from "../query-artifact-cache";

const ref = { threadId: "thread", requestSeq: 1, sourceKind: "query_stage", sourceRef: "query", queryRunId: "query-1" };
const state = { threadId: "thread", userId: "user", queryArtifactRef: ref } as any;
const datasets = [
  { subQuestion: "Supplier batches", data: [{ batch: "B1", quantity: 12 }] },
  { subQuestion: "Recall", data: [{ recall: "R1", status: "running" }] },
  { subQuestion: "Repairs", data: [] },
  { subQuestion: "Personnel", data: [{ person: "P1" }] },
];

describe("report evidence across current and persisted queries", () => {
  beforeEach(() => {
    store.verify.mockReset().mockResolvedValue(undefined);
    store.run = { id: "query-1", threadId: "thread", requestSeq: 1, status: "completed", datasetsPayload: datasets };
  });
  it("keeps every dataset, including successful empty evidence, regardless of the last table", async () => {
    const runtime = new cache.QueryArtifactCache();
    const config = { configurable: { queryArtifactCache: runtime, user_id: "user", domainId: "domain" } };
    runtime.setQueryResult(ref, { datasets });
    const first = await cache.resolveAnalysisEvidence(state, config);
    expect(first?.datasets).toHaveLength(4);
    expect(first?.datasets.find((d) => d.title === "Repairs")?.data).toEqual([]);
    runtime.setQueryResult(ref, { datasets: [...datasets].reverse() });
    const second = await cache.resolveAnalysisEvidence(state, config);
    expect(second?.datasets.map((d) => d.id).sort()).toEqual(first?.datasets.map((d) => d.id).sort());
    expect(second?.version).toBe(first?.version);
  });
  it("rehydrates the current reference after losing the request cache", async () => {
    store.run.abcCodes = datasets.map((_, index) => `query_${index}()`);
    const restored = await cache.resolveAnalysisEvidence(state, { configurable: { user_id: "user", domainId: "domain" } });
    expect(restored?.datasets).toHaveLength(4);
    expect(restored?.datasets[2]?.program).toBe("query_2()");
    expect(restored?.datasets[3]?.program).toBe("query_3()");
    expect(store.verify).toHaveBeenCalledWith("thread", "user", "domain");
  });
  it("retains the current planning ontology without re-fetching it or carrying stale turn metadata", async () => {
    const ontology = { requestSeq: 2, capturedAt: "2026-11-13T00:00:00Z", markdown: "Classes: product, assembly, replacement. Replacement installs batch into product." };
    const config = { configurable: { user_id: "user", domainId: "domain", requestSeq: 2 } };
    const current = await cache.resolveAnalysisEvidence({ ...state, reportOntology: ontology }, config);
    expect(current?.ontology).toEqual({ capturedAt: ontology.capturedAt, markdown: ontology.markdown });
    const nextTurn = await cache.resolveAnalysisEvidence({ ...state, reportOntology: ontology }, {
      configurable: { ...config.configurable, requestSeq: 3 },
    });
    expect(nextTurn?.ontology).toBeUndefined();
    expect(current?.version).not.toBe(nextTurn?.version);
    expect(current?.datasets.map((d) => d.id)).toEqual(nextTurn?.datasets.map((d) => d.id));
  });
  it("allows a completed empty run to be referenced by a later analysis", () => {
    expect(cache.hasUsableQueryRun({ ...store.run, datasetsPayload: [{ data: [] }] })).toBe(true);
  });
  it("rejects unauthorized persisted evidence instead of falling back to another table", async () => {
    store.verify.mockRejectedValue(new Error("Access denied"));
    await expect(cache.resolveAnalysisEvidence(state, { configurable: { user_id: "user", domainId: "other" } })).rejects.toThrow("Access denied");
  });
  it("keeps source truncation distinct from an empty complete result", async () => {
    store.run.datasetsPayload = [{ subQuestion: "Limited", data: [], truncated: true, rowLimit: 10 }];
    const restored = await cache.resolveAnalysisEvidence(state, { configurable: { user_id: "user", domainId: "domain" } });
    expect(restored?.datasets[0]?.truncated).toBe(true);
    expect(restored?.sourceComplete).toBe(false);
  });
});
