import { beforeEach, describe, expect, it, vi } from "vitest";
import { createReportEvidence } from "../evidence";
import { buildReportContract } from "../contract";

const fake = vi.hoisted(() => ({ context: "", tools: [] as any[] }));
vi.mock("../../../../core/agent-loop/agent-loop", () => ({
  runAgentLoop: async (prompt: string, state: any) => {
    fake.context = prompt;
    fake.tools = state.tools;
    const finish = state.tools.find((tool: any) => tool.name === "finish_scope_review");
    await finish.execute("finish", { checks: [{ id: "requirement", passed: true, reason: "Query covers scope" }], issues: [] });
  },
}));
vi.mock("../../../../core/agent-loop/session-run", () => ({ createHarnessRun: vi.fn() }));
vi.mock("../../../../core/prompts/loader", () => ({ renderPrompt: () => "scope prompt" }));
vi.mock("../../../../infrastructure/harness-sessions", () => ({ harnessSessions: {
  create: vi.fn(), beginTurn: async () => ({ turnId: "turn" }), finishTurn: vi.fn(),
} }));

import { reviewReportScope } from "../scope-review";

describe("report scope review context", () => {
  beforeEach(() => { fake.context = ""; fake.tools = []; });

  it("sends retained ontology and query definitions inline while keeping the complete evidence accessible", async () => {
    const evidence = createReportEvidence({ queryRunId: "query", completed: true, datasets: [{
      subQuestion: "Downstream closure", data: [{ batch_no: "B-1" }],
      dsl: { path: ["supplied_by", "produces"] }, abcProgram: { code: "visited-set fixed point" },
    }] as any, ontology: { capturedAt: "2026-11-26T00:00:00Z", markdown: "batch -> process -> batch" } });
    const contract = { ...buildReportContract("Investigate supplier batches", "Write a report"), requirements: [{ id: "requirement", text: "All downstream batches" }] };
    const review = await reviewReportScope({ contract, evidence, statistics: new Map(), config: { baseUrl: "https://model", apiKey: "key", modelName: "model" },
      contextWindow: 100_000, parentSessionId: "parent", signal: new AbortController().signal });

    const context = JSON.parse(fake.context);
    expect(context.evidence.ontology.markdown).toContain("batch -> process -> batch");
    expect(context.definitions[0]).toMatchObject({ dsl: { path: ["supplied_by", "produces"] }, program: "visited-set fixed point" });
    expect(fake.tools.map((tool) => tool.name)).toEqual(expect.arrayContaining(["read_report_evidence", "read_report_ontology", "finish_scope_review"]));
    expect(review.checks[0]).toMatchObject({ id: "requirement", passed: true });
  });
});
