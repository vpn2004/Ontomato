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

/** Inline small definitions once; larger packets stay accessible through the evidence tools. */
function buildScopeReviewContext(input: Parameters<typeof reviewReportScope>[0]): string {
  const evidence = JSON.parse(reportEvidenceContext(input.evidence, input.locale, 8_000));
  const newDatasets = input.evidence.datasets.filter((dataset) => !input.previousReview?.evidenceIds.includes(dataset.id));
  let remaining = Math.min(48_000, Math.floor(input.contextWindow / 2));
  const ontology = input.evidence.ontology?.markdown;
  if (ontology && !input.previousReview && ontology.length <= remaining * 2 / 3) {
    evidence.ontology.markdown = ontology;
    remaining -= ontology.length;
  }
  const definitions = newDatasets.flatMap((dataset) => {
    if (!dataset.program && dataset.dsl === undefined) return [];
    const definition = { datasetId: dataset.id, dsl: dataset.dsl, program: dataset.program };
    const size = JSON.stringify(definition).length;
    if (size > remaining) return [];
    remaining -= size;
    return [definition];
  });
  return JSON.stringify({ contract: input.contract, previousReview: input.previousReview,
    newEvidenceIds: newDatasets.map((dataset) => dataset.id), definitions, evidence,
    statistics: [...input.statistics.values()] });
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
      checks: { type: "array", items: { type: "object", properties: { id: { type: "string", enum: input.contract.requirements.map((item) => item.id) }, passed: { type: "boolean" }, reason: { type: "string" } }, required: ["id", "passed", "reason"] } },
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
    await runAgentLoop(buildScopeReviewContext(input), {
      systemPrompt: renderPrompt("standard-chat.report.scope-review", { currentTime: new Date().toISOString(), locale: input.locale ?? "follow the original user question" }),
      messages: [], tools, isStreaming: false,
    }, { ...input.config, llmLog: { agentName: "report-scope-reviewer", agentRunId: turnId } }, () => {}, input.signal, {
      session: await createHarnessRun(harnessSessions, { sessionId, turnId, contextWindow: input.contextWindow,
        promptKeys: { system: "standard-chat.report.compaction.system", summarize: "standard-chat.report.compaction.summarize", update: "standard-chat.report.compaction.update" } }),
      retryModelTimeoutOnce: true,
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
