import { randomUUID } from "node:crypto";
import { z } from "zod";
import { resolveModelForRole } from "../../../config/model-resolver";
import { runAgentLoop } from "../../../core/agent-loop/agent-loop";
import { createHarnessRun } from "../../../core/agent-loop/session-run";
import type { AgentState, AgentTool, ModelConfig } from "../../../core/agent-loop/types";
import { renderPrompt } from "../../../core/prompts/loader";
import { harnessSessions } from "../../../infrastructure/harness-sessions";
import { measureStage } from "../../../logging/stage-timing";
import { buildReportContract, exactIdIssues, REPORT_SUBMISSION_PARAMETERS, reviseReportSubmission, validateReportSubmission, type ReportContract, type ReportSubmission } from "./contract";
import { createReportEvidenceTools, evidenceHash, reportEvidenceContext, reportEvidenceVersion, type EvidenceStatistic, type ReportEvidence } from "./evidence";
import { openReportCheckpoint, saveReportCheckpoint, type ReportCheckpoint } from "./store";
import { reviewReportScope, type ReportScopeReview } from "./scope-review";
import { createReportBudget } from "./budget";
import { buildCollectionContext, collectionSelectionsParameter } from "./collection-context";
import { buildReportReviewChanges } from "./review-memory";

const compactionPromptKeys = {
  system: "standard-chat.report.compaction.system",
  summarize: "standard-chat.report.compaction.summarize",
  update: "standard-chat.report.compaction.update",
};
const reviewSchema = z.object({ checks: z.array(z.object({ id: z.string(), passed: z.boolean(), reason: z.string() })), issues: z.array(z.string()) });
type ReportReview = z.infer<typeof reviewSchema>;
interface ReviewedSubmission {
  report: ReportSubmission;
  evidenceVersion: string;
  evidenceIds: string[];
  statistics: EvidenceStatistic[];
  verdict: ReportReview;
}
export interface ReportHarnessInput {
  threadId: string;
  requestSeq: number;
  question: string;
  instruction: string;
  evidence: ReportEvidence;
  methodology?: string;
  locale?: string;
  signal?: AbortSignal;
  onProgress?: (message: string) => void;
  onCheckpoint?: (resumable: boolean) => void;
  seed?: Pick<ReportCheckpoint, "statistics" | "report" | "review">;
  collect?: (question: string, signal: AbortSignal) => Promise<ReportEvidence>;
}
export interface ReportHarnessResult {
  markdown: string;
  status: "verified" | "partial";
  issues: string[];
}

export function formatReportResult(report: ReportHarnessResult, locale?: string): string {
  const status = report.status === "verified"
    ? (locale?.startsWith("zh") ? "报告已完成证据与范围核验。" : "Report evidence and scope checks completed.")
    : (locale?.startsWith("zh") ? "阶段性报告：仍有未解决的证据或核验问题，不能作为完整调查结论。" : "Partial report: evidence or verification gaps remain; this is not a complete investigation.");
  return `> ${status}\n\n${report.markdown}${report.issues.length ? `\n\n---\n${report.issues.map((issue) => `- ${issue}`).join("\n")}` : ""}`;
}

/** Read-only investigation with durable evidence and an explicit completion gate. */
export async function runReportHarness(input: ReportHarnessInput): Promise<ReportHarnessResult> {
  input.signal?.throwIfAborted();
  const contract = buildReportContract(input.question, input.instruction);
  const { sessionId, checkpoint } = await openReportCheckpoint(input.threadId, input.requestSeq, contract, input.evidence);
  input.onCheckpoint?.(checkpoint.phase !== "completed");
  const { turnId } = await harnessSessions.beginTurn(sessionId);
  const budget = createReportBudget();
  const failure = new AbortController();
  const signal = AbortSignal.any([...(input.signal ? [input.signal] : []), budget.signal, failure.signal]);
  let evidence = checkpoint.evidence;
  const statistics = new Map((checkpoint.statistics.length ? checkpoint.statistics : input.seed?.statistics ?? []).map((s) => [s.id, s]));
  let draft = checkpoint.report ?? input.seed?.report;
  let lastReview = checkpoint.review ?? input.seed?.review;
  let result: ReportHarnessResult | undefined;
  const rejectedSubmissions = new Map<string, number>();
  const reviewCache = new Map<string, ReportReview>();
  let previousFinal: ReviewedSubmission | undefined;
  const save = async (phase: string, review?: { passed: boolean; issues: string[] }) => {
    signal.throwIfAborted();
    if (review) lastReview = review;
    try {
      await saveReportCheckpoint(sessionId, turnId, { contract, evidence, statistics: [...statistics.values()], phase, report: draft, review: lastReview });
    } catch (error) { failure.abort(error); throw error; }
    signal.throwIfAborted();
    input.onCheckpoint?.(phase !== "completed");
  };
  const progress = (zh: string, en: string) => { signal.throwIfAborted(); input.onProgress?.(input.locale?.startsWith("zh") ? zh : en); };
  try {
    const resolved = await resolveModelForRole("general");
    const config: ModelConfig = { baseUrl: resolved.baseUrl, apiKey: resolved.apiKey, modelName: resolved.modelName,
      maxTokens: resolved.maxTokens, modelKwargs: resolved.customRequestParameters,
      timeoutMs: resolved.timeoutSeconds ? resolved.timeoutSeconds * 1000 : 600_000, maxRetries: resolved.maxRetries,
      llmLog: { agentName: "report-investigator", agentRunId: turnId } };
    const scopeCache = new Map<string, ReportScopeReview>();
    let previousScope: ReportScopeReview | undefined;
    const checkScope = async () => {
      const cached = scopeCache.get(evidence.version);
      if (cached) return cached;
      progress("正在核对调查范围与已收集证据", "Checking investigation scope against collected evidence");
      await save("checking_scope");
      const checked = await measureStage("analysis.report.scope", () => reviewReportScope({ contract, evidence, previousReview: previousScope, statistics, config,
        contextWindow: resolved.contextWindow, parentSessionId: sessionId, signal, locale: input.locale }))
        .catch((error) => { failure.abort(error); throw error; });
      scopeCache.set(evidence.version, checked);
      previousScope = checked;
      await save("drafting", checked.issues.length ? { passed: false, issues: checked.issues } : undefined);
      return checked;
    };
    const initialScope = await checkScope();
    const tools = createReportEvidenceTools(() => evidence, statistics, input.locale);
    tools.push({
      name: "check_report_scope", executionMode: "sequential",
      description: "Check stored evidence readiness after completing a batch of necessary collections, before drafting prose. Unchanged evidence reuses the last verdict. This does not approve a report; submission always enforces scope and separate final verification.",
      parameters: { type: "object", properties: {} },
      async execute() { return textResult(await checkScope()); },
    });
    for (const tool of tools) {
      if (!["summarize_report_evidence", "compare_report_sets"].includes(tool.name)) continue;
      const execute = tool.execute;
      tool.execute = async (...args) => {
        const response = await execute(...args);
        await save("drafting");
        return response;
      };
    }
    const repeatedQueries = new Map<string, string>();
    if (input.collect) tools.push({
      name: "collect_report_evidence", executionMode: "sequential",
      description: "Ask a targeted, self-contained READ-ONLY follow-up question only when saved evidence cannot answer a required scope. Use evidenceSelections to supply existing member IDs or path columns instead of rediscovering a previously collected set. Query complete sets with explicit business filters, relationship paths and distinct keys. Do not prescribe an expected count from a previous partial query or change paths to reproduce it. Do not recollect to bypass a preview or recompute supported local statistics. Never request writes or Actions.",
      parameters: { type: "object", properties: { question: { type: "string" }, evidenceSelections: collectionSelectionsParameter }, required: ["question"] },
      async execute(_id, params) {
        signal.throwIfAborted();
        if (typeof params.question !== "string" || !params.question.trim()) throw new Error("A follow-up query question is required.");
        const question = params.question.trim();
        const savedContext = buildCollectionContext(evidence, params.evidenceSelections);
        const key = evidenceHash([question, savedContext]);
        if (repeatedQueries.has(key)) return textResult(`${repeatedQueries.get(key)!}\nThis request was already collected. Complete any other missing scopes, then call check_report_scope before drafting.`);
        progress("正在补充调查证据", "Collecting missing investigation evidence");
        await save("collecting");
        const additional = await budget.collect(() => input.collect!([
          "Read-only supplemental investigation. Answer the specific evidence request below; the original user request supplies authoritative entity and scope context, not a request to repeat the whole report.",
          `Original user request:\n${contract.originalQuestion}`,
          "The supplemental request was generated by an investigator and may contain mistaken intermediate counts or assumptions. Those are hypotheses to test, not acceptance targets. Never change valid relationship paths, business filters, traversal depth or distinct keys just to reproduce a predicted count. Return the actual complete results for the stated business scope, explain discrepancies, and retain the path records and traversal termination evidence needed to verify completeness. No writes or Actions.",
          ...(savedContext ? [`Selected saved evidence for this follow-up:\n${savedContext}`] : []),
          `Supplemental evidence request:\n${question}`,
        ].join("\n\n"), signal), signal);
        signal.throwIfAborted();
        evidence = { ...evidence, datasets: [...new Map([...evidence.datasets, ...additional.datasets].map((d) => [d.id, d])).values()] };
        evidence.version = reportEvidenceVersion(evidence);
        // A failed or truncated collection remains an explicit gap, even if some rows arrived.
        evidence.sourceComplete &&= additional.sourceComplete;
        await save("drafting");
        const context = reportEvidenceContext(additional, input.locale, 24_000);
        repeatedQueries.set(key, context);
        return textResult(`${context}\nEvidence changed; scope approval is pending. Complete any other missing collections, then call check_report_scope before drafting. Submitting prose cannot bypass this check.`);
      },
    });
    const submissionParameters = { ...REPORT_SUBMISSION_PARAMETERS, properties: { ...REPORT_SUBMISSION_PARAMETERS.properties,
      contractVersion: { type: "string", enum: [contract.version] } } };
    const submitTool: AgentTool = {
      name: "submit_report", executionMode: "sequential",
      description: "Submit Markdown, fixed requirement coverage, all evidence-use explanations and important factual claims. Cell claims require datasetId/row/field/value. Statistic claims require statisticId/value. The host checks references and raw values, then independently reviews scope and prose. Rejected submissions return targeted correction requirements.",
      parameters: submissionParameters,
      async execute(_id, params) {
        signal.throwIfAborted();
        const signature = evidenceHash([params, evidence.version, [...statistics.values()]]);
        const checked = validateReportSubmission(params, contract, evidence, statistics);
        draft = checked.report ?? draft;
        let issues = checked.issues;
        if (issues.length === 0) issues = [...(await checkScope()).issues];
        if (checked.report && issues.length === 0) {
          progress("正在核验报告的事实与调查范围", "Verifying report facts and investigation coverage");
          await save("reviewing");
          let review = reviewCache.get(signature);
          if (!review) {
            review = await measureStage("analysis.report.verify", () => reviewReport({ contract, report: checked.report!, evidence, statistics, config,
              scopeReview: scopeCache.get(evidence.version), previous: previousFinal,
              contextWindow: resolved.contextWindow, sessionId, signal, locale: input.locale })).catch((error) => { failure.abort(error); throw error; });
            reviewCache.set(signature, review);
          }
          previousFinal = structuredClone({ report: checked.report, evidenceVersion: evidence.version,
            evidenceIds: evidence.datasets.map((d) => d.id), statistics: [...statistics.values()], verdict: review });
          issues = [...review.issues];
          if (!checked.complete) issues.push("Some required scopes are blocked or source results are incomplete; this cannot be a fully verified investigation.");
        }
        const passed = issues.length === 0;
        const repeats = (rejectedSubmissions.get(signature) ?? 0) + 1;
        if (!passed) rejectedSubmissions.set(signature, repeats);
        await save(passed ? "completed" : "drafting", { passed, issues });
        // Progressing repairs retain the wall-clock budget; repeated identical failures cannot spin forever.
        if (passed || repeats >= 3) {
          if (!draft) throw new Error("No valid report draft was submitted.");
          result = { markdown: draft.markdown, status: passed ? "verified" : "partial", issues };
          await save(passed ? "completed" : "partial", { passed, issues });
          return { ...textResult({ accepted: passed, issues }), terminate: true };
        }
        return textResult({ accepted: false, issues, instruction: "Repair only the gaps and preserve passing requirements. Use revise_report with only changed claims/requirements keyed by ID; omit unchanged Markdown. Read stored evidence before querying again. Every revision is checked as a complete report." });
      },
    };
    tools.push(submitTool, {
      name: "revise_report", executionMode: "sequential",
      description: "Repair the saved submission without regenerating unchanged prose or claims. All fields are optional: send changed claims, requirements or evidenceUse entries keyed by their stable IDs; prefer markdownEdits with unique oldText/newText spans for wording corrections; use full markdown only for a necessary full rewrite. New evidenceUse entries may account for additional collected datasets. The host merges the patch and repeats all completion and authorized-reference checks. Existing requirements and evidence entries cannot be deleted.",
      parameters: { ...submissionParameters, properties: { ...submissionParameters.properties,
        markdownEdits: { type: "array", description: "Atomic non-overlapping edits against the saved draft. Each oldText must occur exactly once; include enough context. Do not combine with markdown. Full report verification still applies.", minItems: 1, maxItems: 100,
          items: { type: "object", properties: { oldText: { type: "string", minLength: 1 }, newText: { type: "string" } }, required: ["oldText", "newText"] } },
      }, required: [] },
      async execute(id, params) {
        signal.throwIfAborted();
        if (!draft) throw new Error("Submit an initial complete report before revising it.");
        return submitTool.execute(id, { ...reviseReportSubmission(draft, params) }, signal);
      },
    });
    // The shared loop settles a full tool batch; a finalized report must not accept later mutations in that batch.
    for (const tool of tools) {
      const execute = tool.execute;
      tool.execute = async (...args) => {
        signal.throwIfAborted();
        if (result) return textResult({ skipped: true, reason: "Report already finalized." });
        return execute(...args);
      };
    }
    const state: AgentState = { systemPrompt: renderPrompt("standard-chat.report.system", { contract: JSON.stringify(contract), methodology: input.methodology || "", currentTime: new Date().toISOString(), locale: input.locale ?? "follow the original user question" }),
      messages: [], tools, isStreaming: false };
    let prematureStops = 0;
    progress("正在汇总全部查询证据并生成报告", "Preparing a report from all query evidence");
    await save("drafting");
    await measureStage("analysis.report.generate", async () => { await runAgentLoop(
      `${reportEvidenceContext(evidence, input.locale)}\nEvidence readiness checks: ${JSON.stringify(initialScope)}\nResolve missing required collection scopes before drafting long prose. Scope approval is not final report approval.\n${statistics.size ? `Saved computation receipts (reuse their IDs; do not recompute unchanged evidence):\n${JSON.stringify([...statistics.values()])}\n` : ""}${draft ? `Previous submission (requires revalidation; use revise_report to repair it):\n${JSON.stringify(draft)}` : ""}\n${lastReview ? `Previous verification feedback:\n${JSON.stringify(lastReview)}` : ""}`,
      state, config, () => {}, signal, {
        session: await createHarnessRun(harnessSessions, { sessionId, turnId, contextWindow: resolved.contextWindow, promptKeys: compactionPromptKeys }),
        onStop: async () => {
          if (result) return undefined;
          if (++prematureStops > 2) throw new Error("Report stopped without submitting its evidence and completion checks.");
          return "The investigation has not been submitted. Continue any necessary evidence work, then call submit_report with every fixed requirement and evidence ID. Plain narrative does not complete this task.";
        },
      });
      signal.throwIfAborted();
      if (!result) throw new Error("The model did not produce a checked report; evidence remains available.");
    });
    await harnessSessions.finishTurn(sessionId, turnId, "completed");
    return result!;
  } catch (error) {
    try {
      await saveReportCheckpoint(sessionId, turnId, { contract, evidence, statistics: [...statistics.values()], phase: input.signal?.aborted ? "cancelled" : "failed",
        report: draft, review: lastReview, error: error instanceof Error ? error.message : String(error) });
    } finally {
      await harnessSessions.finishTurn(sessionId, turnId, input.signal?.aborted ? "cancelled" : "failed");
    }
    throw error;
  } finally { budget.dispose(); }
}

function textResult(value: unknown) {
  return { content: [{ type: "text" as const, text: typeof value === "string" ? value : JSON.stringify(value) }] };
}

async function reviewReport(input: { contract: ReportContract; report: ReportSubmission; evidence: ReportEvidence;
  statistics: Map<string, EvidenceStatistic>; config: ModelConfig; contextWindow: number; sessionId: string; signal: AbortSignal; locale?: string;
  scopeReview?: ReportScopeReview; previous?: ReviewedSubmission;
}): Promise<ReportReview> {
  const sessionId = `report-review:${randomUUID()}`;
  await harnessSessions.create(sessionId, input.sessionId);
  const { turnId } = await harnessSessions.beginTurn(sessionId);
  let review: ReportReview | undefined;
  const tools: AgentTool[] = [...createReportEvidenceTools(() => input.evidence, new Map(input.statistics), input.locale), {
    name: "finish_report_review", executionMode: "sequential",
    description: "Return one verdict per original requirement ID plus all factual or scope problems in the report. Missing or replaced IDs are rejected.",
    parameters: { type: "object", properties: {
      checks: { type: "array", items: { type: "object", properties: { id: { type: "string" }, passed: { type: "boolean" }, reason: { type: "string" } }, required: ["id", "passed", "reason"] } },
      issues: { type: "array", items: { type: "string" } },
    }, required: ["checks", "issues"] },
    async execute(_id, params) {
      input.signal.throwIfAborted();
      const checked = reviewSchema.parse(params);
      const ids = exactIdIssues(checked.checks.map((c) => c.id), input.contract.requirements.map((r) => r.id), "requirement");
      if (ids.length) return textResult({ accepted: false, issues: ids });
      review = { checks: checked.checks, issues: [...checked.issues, ...checked.checks.filter((c) => !c.passed).map((c) => `${c.id}: ${c.reason}`)] };
      return { ...textResult(review), terminate: true };
    },
  }];
  try {
    let stops = 0;
    await runAgentLoop(JSON.stringify({ contract: input.contract, report: input.report,
      scopeReview: input.scopeReview,
      previousReview: input.previous ? { evidenceVersion: input.previous.evidenceVersion, evidenceIds: input.previous.evidenceIds,
        checks: input.previous.verdict.checks, issues: input.previous.verdict.issues } : undefined,
      changes: input.previous ? buildReportReviewChanges(input.previous, input.report, input.evidence.datasets.map((d) => d.id), [...input.statistics.values()]) : undefined,
      hostChecks: { matchedClaimIds: input.report.claims.map((claim) => claim.id),
        scope: "The host matched each listed claim to its exact raw cell or deterministic statistic value and JSON type. This does NOT validate the claim's business interpretation, query scope, or the report's uncited prose." },
      statistics: [...input.statistics.values()], evidence: JSON.parse(reportEvidenceContext(input.evidence, input.locale, 65_000)) }),
      { systemPrompt: renderPrompt("standard-chat.report.review", { currentTime: new Date().toISOString(), locale: input.locale ?? "follow the original user question" }), messages: [], tools, isStreaming: false },
      { ...input.config, llmLog: { agentName: "report-reviewer", agentRunId: turnId } }, () => {}, input.signal, {
        session: await createHarnessRun(harnessSessions, { sessionId, turnId, contextWindow: input.contextWindow, promptKeys: compactionPromptKeys }),
        onStop: async () => { if (++stops > 1) throw new Error("Report reviewer did not return structured checks."); return "Return the per-requirement verdict using finish_report_review."; },
      });
    input.signal.throwIfAborted();
    if (!review) throw new Error("No report verification result.");
    await harnessSessions.finishTurn(sessionId, turnId, "completed");
    return review;
  } catch (error) { await harnessSessions.finishTurn(sessionId, turnId, input.signal.aborted ? "cancelled" : "failed"); throw error; }
}
