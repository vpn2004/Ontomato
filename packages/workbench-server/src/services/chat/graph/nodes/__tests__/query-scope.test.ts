import { beforeEach, describe, expect, it, vi } from "vitest";

const fake = vi.hoisted(() => ({ execute: vi.fn(), start: vi.fn(), save: vi.fn() }));
vi.mock("../../../../data-query/query-fact", () => ({ buildQueryRunInput: (value: unknown) => value, toDisplayFact: (value: unknown) => value }));
vi.mock("../../../../data-query/base-query-executor", () => ({ executeBaseQuery: fake.execute, STANDARD_QUERY_BRANCHES: ["abc"] }));
vi.mock("../../../../data-query/query-run-store", () => ({ startQueryRun: fake.start, upsertQueryRun: fake.save, updateQueryRunBackendSessions: vi.fn() }));
vi.mock("../../../../data-query/late-fact", () => ({ startQualityCheckSettlement: vi.fn() }));
vi.mock("../../../../data-query/hot-data/hot-data-utils", () => ({ BRANCH_CN: { abc: "ABC" } }));
vi.mock("../../../../data-query/data-query-policy", () => ({ getAbcQuestionMode: () => "abc" }));
vi.mock("../../utils/query-artifact-cache", () => ({
  getQueryArtifactCache: () => ({ setQueryResult: vi.fn() }), createQueryArtifactRef: () => "query-ref",
  resolvePendingLateFacts: () => undefined, STANDARD_QUERY_SOURCE_KIND: "query_stage", STANDARD_QUERY_SOURCE_REF: "query",
}));
vi.mock("../../../../../i18n", () => ({ t: (key: string) => key, tApp: (key: string) => key }));
vi.mock("../../../../../runtime/defaults", () => ({ runtimeDefaults: () => ({ requestLocaleFallback: "en" }) }));

import { queryNode } from "../query-node";

beforeEach(() => {
  vi.clearAllMocks();
  fake.execute.mockResolvedValue({ winner: "abc", output: { content: "", datasets: [] } });
});

const original = "Investigate supplier S and defect batch D, focus on product P, and extend to ALL supplier batches and related products. Write a lifecycle report.";
const narrowed = "List supplier batches; investigate products associated with defect batch D.";
const state = (nodes: string[], question = narrowed) => ({ userQuestion: original, threadId: "scope-thread", plan: { nodes, steps: [{ type: "query", question }] } }) as any;

describe("initial report query scope", () => {
  it("preserves original supplier-wide requirements when the derived collection plan narrows them", async () => {
    const result = await queryNode(state(["query", "analysis"]));
    expect(result.errors).toBeUndefined();
    const input = fake.execute.mock.calls[0]![0];
    expect(input.queryQuestion).toContain(original);
    expect(input.queryQuestion).toContain(narrowed);
    expect(input.answerMode).toBe("evidence");
    expect(fake.start.mock.calls[0]![0].question).toBe(input.queryQuestion);
    expect(fake.save.mock.calls[0]![0].question).toBe(input.queryQuestion);
  });
  it("preserves explicitly named ordinary-query relationships when planning broadens them", async () => {
    const request = "Count distinct product IDs by supplier through supplied_by, uses_batch and creates only.";
    const expanded = "Count using uses_batch and uses_batch_2/3/4.";
    await queryNode({ ...state(["query"], expanded), userQuestion: request });
    const input = fake.execute.mock.calls[0]![0];
    expect(input.queryQuestion).toContain(request);
    expect(input.queryQuestion).toContain(expanded);
    expect(input.answerMode).toBe("answer");
    expect(input.queryQuestion).not.toContain("traverse the relevant directed paths with visited IDs");
    expect(fake.save.mock.calls[0]![0].question).toBe(input.queryQuestion);
  });
  it("keeps resolved context available for a follow-up without inventing report work", async () => {
    await queryNode({ ...state(["query"], "Count products for the previously selected customer C."), userQuestion: "How many of those products?" });
    const input = fake.execute.mock.calls[0]![0];
    expect(input.queryQuestion).toContain("previously selected customer C");
    expect(input.queryQuestion).toContain("How many of those products?");
    expect(input.answerMode).toBe("answer");
  });
  it("adds no context wrapper when an ordinary question was not rewritten", async () => {
    await queryNode(state(["query"], original));
    expect(fake.execute.mock.calls[0]![0]).toMatchObject({ queryQuestion: original, answerMode: "answer" });
  });
});
