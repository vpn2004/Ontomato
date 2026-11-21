import { beforeEach, describe, expect, it, vi } from "vitest";
import { createReportEvidence } from "../evidence";
import { buildReportContract } from "../contract";

const fake = vi.hoisted(() => ({
  mode: "valid", calls: [] as string[], prompts: [] as string[], reviewInputs: [] as any[], scopeCalls: 0, scopeSizes: [] as number[], scopeInputs: [] as any[], saves: [] as any[], stages: [] as Array<{ name: string; outcome: string }>, seq: 0, submission: {} as any,
}));
vi.mock("../../../../config/model-resolver", () => ({ resolveModelForRole: async () => ({ contextWindow: 100000, maxTokens: 10000 }) }));
vi.mock("../../../../core/prompts/loader", () => ({ renderPrompt: (key: string, args: any) => `${key} ${JSON.stringify(args)}` }));
vi.mock("../../../../logging/stage-timing", () => ({ measureStage: async (name: string, run: () => unknown) => {
  try { const result = await run(); fake.stages.push({ name, outcome: "completed" }); return result; }
  catch (error) { fake.stages.push({ name, outcome: "failed" }); throw error; }
} }));
vi.mock("../../../../core/agent-loop/compaction/compact-messages", () => ({ compactContext: async () => ({ compacted: false }) }));
vi.mock("../../../../infrastructure/harness-sessions", () => ({ harnessSessions: {
  create: vi.fn(), beginTurn: async () => ({ turnId: `turn-${++fake.seq}` }), finishTurn: vi.fn(),
  appendMessage: async () => ++fake.seq, writeCompaction: vi.fn(), readContextSnapshot: async () => ({ rows: [], compaction: null }),
} }));
vi.mock("../store", () => ({
  openReportCheckpoint: async (_t: string, _r: number, contract: unknown, evidence: unknown) => ({ sessionId: "session", checkpoint: { contract, evidence, statistics: [], phase: "collecting" } }),
  saveReportCheckpoint: async (_s: string, _t: string, value: unknown) => { fake.saves.push(structuredClone(value)); },
}));
vi.mock("../../../../core/agent-loop/llm-stream", () => ({
  streamChatCompletion: async function* (config: any, _system: string, messages: any[]) {
    fake.prompts.push(_system);
    expect(_system).toContain(new Date().toISOString().slice(0, 10));
    yield { type: "thinking_delta", delta: "checking" };
    if (config.llmLog.agentName === "report-scope-reviewer") {
      fake.scopeCalls++;
      const input = JSON.parse(messages[0].content[0].text);
      expect(input).not.toHaveProperty("report");
      fake.scopeSizes.push(input.evidence.datasets.length);
      fake.scopeInputs.push(input);
      const missingScope = fake.mode === "scope-gap" || (fake.mode === "scope-regression" && fake.scopeCalls > 1) || (["collect-new", "collect-batch", "collect-fails"].includes(fake.mode) && fake.scopeCalls === 1);
      return { role: "assistant", content: [{ type: "toolCall", id: "scope", name: "finish_scope_review", arguments: {
        checks: input.contract.requirements.map((r: any) => ({ id: r.id, passed: !missingScope, reason: "Scope checked against executed queries" })),
        issues: missingScope ? ["Only one expansion was queried; downstream termination evidence is missing"] : [],
      } }], stopReason: "tool_calls" };
    }
    fake.calls.push(config.llmLog.agentName);
    if (fake.mode === "model-error" || (fake.mode === "collect-fails" && fake.calls.length > 1)) return { role: "assistant", content: [], stopReason: "error", errorMessage: "Model unavailable" };
    if (fake.mode === "premature" && fake.calls.length === 1) return { role: "assistant", content: [{ type: "text", text: "Done" }], stopReason: "stop" };
    if (["collect", "collect-new", "collect-fails", "scope-regression"].includes(fake.mode) && fake.calls.length === 1) return { role: "assistant", content: [{ type: "toolCall", id: "collect", name: "collect_report_evidence",
      arguments: { question: "Find downstream products; expected 69" } }], stopReason: "tool_calls" };
    if (fake.mode === "collect-batch" && fake.calls.length === 1) return { role: "assistant", content: [
      { type: "toolCall", id: "collect-a", name: "collect_report_evidence", arguments: { question: "Collect missing material paths" } },
      { type: "toolCall", id: "collect-b", name: "collect_report_evidence", arguments: { question: "Collect missing lifecycle paths" } },
      { type: "toolCall", id: "scope-after-batch", name: "check_report_scope", arguments: {} },
    ], stopReason: "tool_calls" };
    if (fake.mode === "collect-context" && fake.calls.length === 1) return { role: "assistant", content: [
      { type: "toolCall", id: "selected", name: "collect_report_evidence", arguments: { question: "Read lifecycle details", evidenceSelections: [{ datasetId: fake.submission.evidenceUse[0].id, fields: ["status"] }] } },
      { type: "toolCall", id: "unselected", name: "collect_report_evidence", arguments: { question: "Read lifecycle details" } },
      { type: "toolCall", id: "duplicate", name: "collect_report_evidence", arguments: { question: "Read lifecycle details", evidenceSelections: [{ datasetId: fake.submission.evidenceUse[0].id, fields: ["status"] }] } },
    ], stopReason: "tool_calls" };
    if (config.llmLog.agentName === "report-reviewer") {
      const input = JSON.parse(messages[0].content[0].text);
      fake.reviewInputs.push(input);
      expect(input.hostChecks.matchedClaimIds).toEqual(input.report.claims.map((claim: any) => claim.id));
      const failed = fake.mode === "review-fails" || (fake.mode === "prose-repair" && input.report.markdown.includes("not executed"));
      return { role: "assistant", content: [{ type: "toolCall", id: "review", name: "finish_report_review", arguments: {
        checks: input.contract.requirements.map((r: any) => ({ id: r.id, passed: !failed, reason: "Verified scope" })),
        issues: failed ? ["Supplier-wide scope omitted or recall prose contradicts its evidence"] : [],
      } }], stopReason: "tool_calls" };
    }
    if (fake.mode === "progressive-repair") {
      const attempt = fake.calls.length;
      const report = structuredClone(fake.submission);
      report.claims = Array.from({ length: 4 }, (_, n) => ({ ...report.claims[0], id: `claim-${n}`, value: n < attempt - 1 ? "running" : "not executed" }));
      return { role: "assistant", content: [{ type: "toolCall", id: `submit-${attempt}`, name: "submit_report", arguments: report }], stopReason: "tool_calls" };
    }
    if (fake.mode === "terminal-batch") return { role: "assistant", content: [
      { type: "toolCall", id: "submit", name: "submit_report", arguments: fake.submission },
      { type: "toolCall", id: "late-collect", name: "collect_report_evidence", arguments: { question: "Do not execute after completion" } },
    ], stopReason: "tool_calls" };
    if (fake.mode === "prose-repair") return { role: "assistant", content: [{ type: "toolCall", id: `prose-${fake.calls.length}`,
      name: fake.calls.length === 1 ? "submit_report" : "revise_report", arguments: fake.calls.length === 1
        ? { ...fake.submission, markdown: "Recall is not executed." }
        : { markdownEdits: [{ oldText: "Recall is not executed.", newText: "Recall is running." }] },
    }], stopReason: "tool_calls" };
    if (fake.mode === "repair") {
      const first = fake.calls.length === 1;
      return { role: "assistant", content: [{ type: "toolCall", id: `repair-${fake.calls.length}`,
        name: first ? "submit_report" : "revise_report",
        arguments: first ? { ...fake.submission, claims: [{ ...fake.submission.claims[0], value: "not executed" }] }
          : { claims: fake.submission.claims },
      }], stopReason: "tool_calls" };
    }
    return { role: "assistant", content: [{ type: "toolCall", id: `submit-${fake.calls.length}`, name: "submit_report", arguments: fake.submission }], stopReason: "tool_calls" };
  },
}));
import { runReportHarness } from "../harness";
import { harnessSessions } from "../../../../infrastructure/harness-sessions";

const evidence = createReportEvidence({ queryRunId: "q", completed: true, datasets: [{ subQuestion: "Recall", data: [{ status: "running" }] }] as any });
const input = { threadId: "thread", requestSeq: 0, question: "Investigate all supplier batches", instruction: "Write a report", evidence };
const contract = buildReportContract(input.question, input.instruction);
beforeEach(() => {
  fake.mode = "valid"; fake.calls = []; fake.prompts = []; fake.reviewInputs = []; fake.scopeCalls = 0; fake.scopeSizes = []; fake.scopeInputs = []; fake.saves = []; fake.stages = []; fake.seq = 0; vi.clearAllMocks();
  fake.submission = {
    contractVersion: contract.version, markdown: "Recall is running.",
    requirements: contract.requirements.map((r) => ({ id: r.id, status: "supported", explanation: "Full scope", evidenceIds: [evidence.datasets[0]!.id] })),
    evidenceUse: [{ id: evidence.datasets[0]!.id, reason: "Recall status" }],
    claims: [{ id: "status", kind: "cell", statement: "Recall is running", datasetId: evidence.datasets[0]!.id, row: 0, field: "status", value: "running" }],
  };
});

describe("real report loop with deterministic model responses", () => {
  it("passes the request language preference through collection review, drafting and final review", async () => {
    expect((await runReportHarness({ ...input, locale: "zh-CN" })).status).toBe("verified");
    expect(fake.prompts).toHaveLength(3);
    for (const prompt of fake.prompts) expect(prompt).toContain('"locale":"zh-CN"');
  });
  it("forwards selected evidence to collection and only reuses identical question/context pairs", async () => {
    fake.mode = "collect-context";
    const collect = vi.fn(async (_question: string) => evidence);
    expect((await runReportHarness({ ...input, collect })).status).toBe("verified");
    expect(collect).toHaveBeenCalledTimes(2);
    const selected = collect.mock.calls[0]![0];
    expect(selected).toContain(evidence.datasets[0]!.id);
    expect(selected).toContain('"rows":[{"status":"running"}]');
    expect(selected).toContain("Read lifecycle details");
    expect(collect.mock.calls[1]![0]).not.toContain("Selected saved evidence");
  });
  it("blocks complete-looking prose on an unresolved scope without paying for repeated full-report reviews", async () => {
    fake.mode = "scope-gap";
    const result = await runReportHarness(input);
    expect(result.status).toBe("partial");
    expect(result.issues.join(" ")).toContain("downstream termination");
    expect(fake.scopeCalls).toBe(1);
    expect(fake.calls).not.toContain("report-reviewer");
  });
  it("checks scope once after a batch of collections and reuses it at submission", async () => {
    fake.mode = "collect-batch";
    const extra = ["material", "lifecycle"].map((queryRunId) => createReportEvidence({ queryRunId, completed: true, datasets: [{ data: [{ result: queryRunId }] }] } as any));
    fake.submission.evidenceUse.push(...extra.map((e) => ({ id: e.datasets[0]!.id, reason: "Missing scope" })));
    let collected = 0;
    expect((await runReportHarness({ ...input, collect: async () => extra[collected++]! })).status).toBe("verified");
    expect(collected).toBe(2);
    expect(fake.scopeSizes).toEqual([1, 3]);
    expect(fake.calls.filter((name) => name === "report-reviewer")).toHaveLength(1);
  });
  it("preserves discovered scope gaps when collection fails before a report can be drafted", async () => {
    fake.mode = "collect-fails";
    await expect(runReportHarness({ ...input, collect: async () => { throw new Error("Source unavailable"); } })).rejects.toThrow();
    expect(fake.saves.at(-1).review).toEqual(expect.objectContaining({ passed: false, issues: expect.arrayContaining(["Only one expansion was queried; downstream termination evidence is missing"]) }));
  });
  it("checks evidence readiness before drafting and still verifies the final report separately", async () => {
    expect((await runReportHarness(input)).status).toBe("verified");
    expect(fake.scopeCalls).toBe(1);
    expect(fake.saves.some((save) => save.phase === "checking_scope" && !save.report)).toBe(true);
    expect(fake.calls).toContain("report-reviewer");
  });
  it("rechecks scope after supplemental evidence changes its version and allows the repaired report", async () => {
    fake.mode = "collect-new";
    const extra = createReportEvidence({ queryRunId: "complete-scope", completed: true, datasets: [{ data: [{ newObjects: 0 }] }] as any });
    fake.submission.evidenceUse.push({ id: extra.datasets[0]!.id, reason: "Traversal termination" });
    const result = await runReportHarness({ ...input, collect: async () => extra });
    expect(result.status).toBe("verified");
    expect(fake.scopeCalls).toBe(2);
    expect(fake.calls.filter((name) => name === "report-reviewer")).toHaveLength(1);
    expect(fake.saves.at(-1).evidence.datasets).toHaveLength(2);
    expect(fake.scopeInputs[1].previousReview).toMatchObject({ evidenceVersion: evidence.version, evidenceIds: [evidence.datasets[0]!.id] });
    expect(fake.scopeInputs[1].newEvidenceIds).toEqual([extra.datasets[0]!.id]);
  });
  it("does not let an earlier scope approval override a new gap revealed by additional evidence", async () => {
    fake.mode = "scope-regression";
    const extra = createReportEvidence({ queryRunId: "new-gap", completed: true, datasets: [{ data: [{ newPath: "replacement" }] }] } as any);
    fake.submission.evidenceUse.push({ id: extra.datasets[0]!.id, reason: "New path evidence" });
    expect((await runReportHarness({ ...input, collect: async () => extra })).status).toBe("partial");
    expect(fake.scopeInputs[1].previousReview.checks.every((c: any) => c.passed)).toBe(true);
    expect(fake.calls).not.toContain("report-reviewer");
  });
  it("can finish report verification after a collection longer than the reasoning budget", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "Date", "performance"] });
    try {
      vi.setSystemTime(new Date("2026-01-01T12:00:00Z"));
      fake.mode = "collect";
      const outcome = expect(runReportHarness({ ...input, collect: () => new Promise((resolve) => setTimeout(() => resolve(evidence), 1_500_000)) })).resolves.toMatchObject({ status: "verified" });
      await vi.advanceTimersByTimeAsync(1_500_001);
      await outcome;
      expect(fake.saves.at(-1).phase).toBe("completed");
      expect(vi.getTimerCount()).toBe(0);
    } finally { vi.useRealTimers(); }
  });
  it("does not collect more evidence or overwrite completion after a terminal submission in the same tool batch", async () => {
    fake.mode = "terminal-batch";
    const collect = vi.fn(async () => evidence);
    expect((await runReportHarness({ ...input, collect })).status).toBe("verified");
    expect(collect).not.toHaveBeenCalled();
    expect(fake.saves.at(-1).phase).toBe("completed");
  });
  it("passes the original scope with a supplemental request instead of delegating only a model hypothesis", async () => {
    fake.mode = "collect";
    const collect = vi.fn(async () => evidence);
    expect((await runReportHarness({ ...input, collect })).status).toBe("verified");
    const question = (collect.mock.calls[0] as unknown as [string])[0];
    expect(question).toContain(input.question);
    expect(question).toContain("Find downstream products; expected 69");
  });
  it("allows more than three progressing corrections before independent review", async () => {
    fake.mode = "progressive-repair";
    expect((await runReportHarness(input)).status).toBe("verified");
    expect(fake.calls.filter((call) => call === "report-investigator")).toHaveLength(5);
    expect(fake.calls.filter((call) => call === "report-reviewer")).toHaveLength(1);
  });
  it("preserves prior review gaps when a recovered report fails before a new submission", async () => {
    fake.mode = "model-error";
    const review = { passed: false, issues: ["Unresolved downstream scope"] };
    await expect(runReportHarness({ ...input, seed: { statistics: [], report: fake.submission, review } })).rejects.toThrow();
    expect(fake.saves[0].review).toEqual(review);
    expect(fake.saves.at(-1).review).toEqual(review);
  });
  it("rechecks full semantics after a targeted prose edit while reusing unchanged scope", async () => {
    fake.mode = "prose-repair";
    expect(await runReportHarness(input)).toEqual({ status: "verified", markdown: "Recall is running.", issues: [] });
    expect(fake.calls.filter((name) => name === "report-reviewer")).toHaveLength(2);
    expect(fake.scopeCalls).toBe(1);
    expect(fake.saves.at(-1).report.claims).toEqual(fake.submission.claims);
  });
  it("gives final verification the current scope findings without treating them as report approval", async () => {
    fake.mode = "review-fails";
    expect((await runReportHarness(input)).status).toBe("partial");
    expect(fake.reviewInputs).toHaveLength(1);
    expect(fake.reviewInputs[0].scopeReview).toMatchObject({ evidenceVersion: evidence.version, issues: [],
      checks: [{ id: contract.requirements[0]!.id, passed: true, reason: "Scope checked against executed queries" }] });
  });
  it("supplies the previous final verdict and exact changed content on a progressing repair", async () => {
    fake.mode = "prose-repair";
    expect((await runReportHarness(input)).status).toBe("verified");
    const [first, second] = fake.reviewInputs;
    expect(first.previousReview).toBeUndefined();
    expect(second.previousReview).toMatchObject({ evidenceVersion: evidence.version,
      issues: expect.arrayContaining(["Supplier-wide scope omitted or recall prose contradicts its evidence"]),
      checks: [{ id: contract.requirements[0]!.id, passed: false, reason: "Verified scope" }] });
    expect(second.changes).toMatchObject({ markdownChanged: true, changedClaimIds: [], changedRequirementIds: [],
      newEvidenceIds: [], changedStatisticIds: [] });
    expect(second.changes.markdownChange).toEqual({ oldText: "not executed", newText: "running" });
    expect(second.report.markdown).toBe("Recall is running.");
  });
  it("validates a targeted claim repair without regenerating its saved Markdown", async () => {
    fake.mode = "repair";
    const result = await runReportHarness(input);
    expect(result).toEqual({ markdown: fake.submission.markdown, status: "verified", issues: [] });
    expect(fake.calls).toEqual(["report-investigator", "report-investigator", "report-reviewer"]);
    expect(fake.saves.at(-1).report.requirements).toEqual(fake.submission.requirements);
  });
  it("records a failed report stage when the model ends without a submitted result", async () => {
    fake.mode = "model-error";
    await expect(runReportHarness(input)).rejects.toThrow("checked report");
    expect(fake.stages).toContainEqual({ name: "analysis.report.generate", outcome: "failed" });
    expect(fake.saves.at(-1).phase).toBe("failed");
  });
  it("persists evidence and requires an independent review before verified completion", async () => {
    const result = await runReportHarness(input);
    expect(result.status).toBe("verified");
    expect(fake.calls).toEqual(["report-investigator", "report-reviewer"]);
    expect(fake.saves[0].evidence.datasets).toHaveLength(1);
    expect(fake.saves.at(-1).review.passed).toBe(true);
  });
  it("blocks plain-text premature completion and continues the same investigation", async () => {
    fake.mode = "premature";
    expect((await runReportHarness(input)).status).toBe("verified");
    expect(fake.calls).toEqual(["report-investigator", "report-investigator", "report-reviewer"]);
  });
  it("never spends a reviewer call on a known incorrect source value", async () => {
    fake.submission.claims[0].value = "not executed";
    const result = await runReportHarness(input);
    expect(result.status).toBe("partial");
    expect(result.issues.join(" ")).toContain("source cell");
    expect(fake.calls).not.toContain("report-reviewer");
  });
  it("retains an unverified terminal state when targeted revisions cannot fix scope", async () => {
    fake.mode = "review-fails";
    const result = await runReportHarness(input);
    expect(result.status).toBe("partial");
    expect(result.issues.join(" ")).toContain("Supplier-wide");
    expect(fake.saves.at(-1).phase).toBe("partial");
    expect(fake.calls.filter((call) => call === "report-reviewer")).toHaveLength(1);
  });
  it("does not begin or invoke a model after cancellation", async () => {
    await expect(runReportHarness({ ...input, signal: AbortSignal.abort() })).rejects.toThrow();
    expect(fake.calls).toEqual([]);
    expect(harnessSessions.finishTurn).not.toHaveBeenCalled();
  });
});
