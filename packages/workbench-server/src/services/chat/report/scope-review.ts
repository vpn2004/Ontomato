import { randomUUID } from "node:crypto";
import { z } from "zod";
import { runAgentLoop } from "../../../core/agent-loop/agent-loop";
import { createHarnessRun } from "../../../core/agent-loop/session-run";
import type { AgentTool, ModelConfig } from "../../../core/agent-loop/types";
import { renderPrompt } from "../../../core/prompts/loader";
import { harnessSessions } from "../../../infrastructure/harness-sessions";
import { exactIdIssues, type ReportContract } from "./contract";
import { createReportEvidenceTools, reportEvidenceContext, type EvidenceStatistic, type ReportEvidence } from "./evidence";

const verdictSchema = z.object({
  checks: z.array(z.object({ id: z.string(), passed: z.boolean(), reason: z.string().min(1) })),
  issues: z.array(z.string()),
});

export interface ReportScopeReview {
  evidenceVersion: string;
  evidenceIds: string[];
  checks: Array<{ id: string; passed: boolean; reason: string }>;
  issues: string[];
}

/** Check collection scope before long prose makes omissions harder to see or repair. */
export async function reviewReportScope(input: {
  contract: ReportContract; evidence: ReportEvidence; previousReview?: ReportScopeReview; statistics: Map<string, EvidenceStatistic>;
  config: ModelConfig; contextWindow: number; parentSessionId: string; signal: AbortSignal; locale?: string;
}): Promise<ReportScopeReview> {
  input.signal.throwIfAborted();
  const sessionId = `report-scope:${randomUUID()}`;
  await harnessSessions.create(sessionId, input.parentSessionId);
  const { turnId } = await harnessSessions.beginTurn(sessionId);
  let verdict: ReportScopeReview | undefined;
  const tools: AgentTool[] = [...createReportEvidenceTools(() => input.evidence, input.statistics, input.locale), {
    name: "finish_scope_review", executionMode: "sequential",
    description: "Decide whether stored evidence covers each ORIGINAL requirement sufficiently to draft the requested report. Return exact requirement IDs and concrete missing query scopes. This approves evidence readiness only, never the unwritten report or its factual conclusions.",
    parameters: { type: "object", properties: {
      checks: { type: "array", items: { type: "object", properties: { id: { type: "string" }, passed: { type: "boolean" }, reason: { type: "string" } }, required: ["id", "passed", "reason"] } },
      issues: { type: "array", description: "Only unresolved blocking evidence gaps. Empty when ready. Positive findings belong in check reasons.", items: { type: "string" } },
    }, required: ["checks", "issues"] },
    async execute(_id, params) {
      input.signal.throwIfAborted();
      const parsed = verdictSchema.parse(params);
      const invalid = exactIdIssues(parsed.checks.map((item) => item.id), input.contract.requirements.map((item) => item.id), "requirement");
      if (invalid.length) return { content: [{ type: "text", text: JSON.stringify({ accepted: false, issues: invalid }) }] };
      verdict = { evidenceVersion: input.evidence.version, evidenceIds: input.evidence.datasets.map((d) => d.id), checks: parsed.checks,
        issues: [...parsed.issues, ...parsed.checks.filter((item) => !item.passed).map((item) => `${item.id}: ${item.reason}`)] };
      return { content: [{ type: "text", text: JSON.stringify(verdict) }], terminate: true };
    },
  }];
  try {
    let stops = 0;
    await runAgentLoop(JSON.stringify({ contract: input.contract, statistics: [...input.statistics.values()],
      previousReview: input.previousReview,
      newEvidenceIds: input.evidence.datasets.filter((d) => !input.previousReview?.evidenceIds.includes(d.id)).map((d) => d.id),
      evidence: JSON.parse(reportEvidenceContext(input.evidence, input.locale, 24_000)) }), {
      systemPrompt: renderPrompt("standard-chat.report.scope-review", { currentTime: new Date().toISOString(), locale: input.locale ?? "follow the original user question" }),
      messages: [], tools, isStreaming: false,
    }, { ...input.config, llmLog: { agentName: "report-scope-reviewer", agentRunId: turnId } }, () => {}, input.signal, {
      session: await createHarnessRun(harnessSessions, { sessionId, turnId, contextWindow: input.contextWindow,
        promptKeys: { system: "standard-chat.report.compaction.system", summarize: "standard-chat.report.compaction.summarize", update: "standard-chat.report.compaction.update" } }),
      onStop: async () => {
        if (++stops > 1) throw new Error("Scope reviewer did not return structured checks.");
        return "Return every original requirement verdict using finish_scope_review.";
      },
    });
    input.signal.throwIfAborted();
    if (!verdict) throw new Error("No evidence scope verification result.");
    await harnessSessions.finishTurn(sessionId, turnId, "completed");
    return verdict;
  } catch (error) {
    await harnessSessions.finishTurn(sessionId, turnId, input.signal.aborted ? "cancelled" : "failed");
    throw error;
  }
}
