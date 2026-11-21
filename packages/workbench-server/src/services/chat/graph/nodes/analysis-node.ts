import { GraphState, GraphUpdate } from "../state";
import { createLogger } from "../../../../logging/logger";
import { t, tApp } from "../../../../i18n";
import { skillRegistryManager } from "../../../../core/skills/registry/manager";
import type { SkillRegistryManager } from "../../../../core/skills/registry/manager";
import { skillRuntime } from "../../../../core/skills/execution/runtime";
import { loadSkillContent } from "../../../../core/skills/discovery/loader";
import { SkillCategory, SkillType } from "../../../../core/skills/constants";
import { createModel, type ModelOptions } from "../../../../config/model-factory";
import { modelAgentName } from "../../../../logging/model-agents";
import type { SkillMeta } from "../../../../core/skills/types";
import {
  stringifyWithLimit,
  truncateText,
} from "../../../../utils/prompt-context";
import { buildModelDataView } from "../../../../utils/model-data-view";
import { renderPrompt } from "../../../../core/prompts/loader";
import {
  createRuntimeArtifactRef,
  getQueryArtifactCache,
  resolveAnalysisEvidence,
} from "../utils/query-artifact-cache";

import { formatReportResult, runReportHarness } from "../../report/harness";
import { createReportEvidence } from "../../report/evidence";
import { executeBaseQuery, DEEP_ANALYSIS_QUERY_BRANCHES } from "../../../data-query/base-query-executor";
import { buildQueryRunInput } from "../../../data-query/query-fact";
import { upsertQueryRun, buildQueryRunId } from "../../../data-query/query-run-store";
import { randomUUID } from "node:crypto";

const logger = createLogger("analysis-node");

async function matchExecutableAnalysisSkills(
  registry: SkillRegistryManager,
  query: string,
  data: Record<string, unknown>[],
  services: { createModel: (opts?: number | ModelOptions) => Promise<any> | any },
  signal?: AbortSignal
): Promise<{ skill: SkillMeta; confidence: number; reason: string }[]> {
  const executableSkills = registry.getSkills({
    type: SkillType.EXECUTABLE,
    category: SkillCategory.ANALYSIS,
  });

  if (executableSkills.length === 0) return [];

  // Analyze data characteristics
  const rowCount = data.length;
  const fields = Array.from(new Set(data.flatMap((row) => Object.keys(row || {}))));
  const dataView = buildModelDataView(data);
  const visibleRows = [...dataView.headRows, ...dataView.tailRows];
  const sampleValues: Record<string, any[]> = {};
  for (const field of fields) {
    sampleValues[field] = visibleRows.map((row) => row[field]);
  }

  const fieldsInfo = fields
    .map((field) => {
      const samples = sampleValues[field].filter((v) => v !== null && v !== undefined);
      const types = samples.map((v) => typeof v);
      const typeStr = types.length > 0 ? types[0] : "unknown";
      return tApp("queryFixed.11", { v0: (field), v1: (typeStr), v2: (samples.length) });
    })
    .join(", ");

  if (!services?.createModel) {
    logger.warn(tApp("queryFixed.23"));
    return executableSkills.map((s) => ({
      skill: s,
      confidence: 0.3,
      reason: t("skill.keywordMatch"),
    }));
  }

  try {
    const model = await services.createModel({ agentName: modelAgentName("analysis") });
    const skillDescriptions = executableSkills
      .map(
        (s) =>
          tApp("queryFixed.12", { v0: (s.id), v1: (s.manifest.title || s.id), v2: (s.manifest.description || tApp("queryFixed.24")), v3: ((s.manifest.tags || []).join(", ")) })
      )
      .join("\n");

    const prompt = renderPrompt("standard-chat.analysis.skill-matcher.user", {
      query,
      rowCount: String(rowCount),
      fieldsInfo,
      skillDescriptions,
    });

    let fullContent = "";
    const stream = await model.stream(prompt, { signal });

    for await (const chunk of stream) {
      const token = chunk.content;
      if (token) {
        fullContent += token;
      }
    }

    const jsonMatch = fullContent.match(/\[[\s\S]*\]/);

    if (!jsonMatch) {
      return [];
    }

    const parsed = JSON.parse(jsonMatch[0]);
    const results = parsed
      .map((p: any) => {
        const matched = executableSkills.find((s) => s.id === p.skillId);
        return matched
          ? {
              skill: matched,
              confidence: p.confidence || 0.5,
              reason: p.reason || t("skill.llmMatch"),
            }
          : null;
      })
      .filter(Boolean) as { skill: SkillMeta; confidence: number; reason: string }[];

    return results;
  } catch (error) {
    logger.error(tApp("queryFixed.25"), error);
    return [];
  }
}

export async function analysisNode(state: GraphState, config?: any): Promise<GraphUpdate> {
  const signal: AbortSignal | undefined = config?.signal ?? config?.configurable?.signal;
  signal?.throwIfAborted();
  if (state.errors?.some((error) => error.node === "query")) return {};
  const locale = config?.configurable?.locale as string | undefined;
  const analysisStep = state.plan?.steps.find((step) => step.type === "analysis");
  const analysisInstruction =
    analysisStep?.type === "analysis" ? analysisStep.instruction : undefined;
  logger.debug(tApp("queryFixed.26"), { instructionLength: analysisInstruction?.length || 0 });

  const onEvent = config?.configurable?.onEvent;
  const domainId = config?.configurable?.domainId as string | undefined;
  const requestSeq = config?.configurable?.requestSeq ?? 0;
  const queryArtifactCache = getQueryArtifactCache(config);
  const usedSkills: string[] = [];

  const sendEvent = (event: any) => {
    if (event.type !== "token") logger.debug(tApp("queryFixed.13", { v0: (event.type) }));
    if (onEvent) onEvent(event);
  };

  const services = { createModel };

  if (!analysisInstruction) {
    const errorMsg = tApp("queryFixed.27");
    const errorEvent = {
      type: "error" as const,
      node: "analysis" as const,
      content: errorMsg,
      timestamp: Date.now(),
    };
    sendEvent(errorEvent);
    return {
      events: [errorEvent],
      errors: [{ node: "analysis", message: errorMsg, timestamp: Date.now() }],
    };
  }

  const evidence = await resolveAnalysisEvidence(state, config);

  if (!evidence) {
    logger.error(tApp("queryFixed.28"));
    const errorMsg = t("analysis.noDataForAnalysis");

    const thinkingEvent = {
      type: "thinking" as const,
      node: "analysis" as const,
      content: `❌ ${errorMsg}`,
      skills: usedSkills,
      timestamp: Date.now(),
    };

    const errorEvent = {
      type: "error" as const,
      node: "analysis" as const,
      content: errorMsg,
      skills: usedSkills,
      timestamp: Date.now(),
    };

    sendEvent(thinkingEvent);
    sendEvent(errorEvent);

    return {
      events: [thinkingEvent, errorEvent],
      errors: [{ node: "analysis", message: errorMsg, timestamp: Date.now() }],
    };
  }

  // Single-table compute skills retain their contract; multi-table reports use explicit evidence tools.
  const data = evidence.datasets.length === 1 ? evidence.datasets[0]!.data : [];

  try {
    const registry = domainId ? await skillRegistryManager.forDomain(domainId) : null;

    sendEvent({
      type: "progress" as const,
      node: "analysis" as const,
      content: t("analysisNode.matchingComputeSkills"),
      timestamp: Date.now(),
    });

    const matchedCalcSkills = registry && evidence.datasets.length === 1
      ? await matchExecutableAnalysisSkills(registry, analysisInstruction, data, services, signal)
      : [];
    const computedResults: Record<string, any> = {};

    if (matchedCalcSkills.length > 0) {
      logger.info(tApp("queryFixed.15", { v0: (matchedCalcSkills.map((m) => m.skill.id).join(", ")) }));

      sendEvent({
        type: "thinking" as const,
        node: "analysis" as const,
        content: `${t("analysisNode.skillMatchDone")}: ${matchedCalcSkills.map((m) => m.skill.manifest.title || m.skill.id).join(", ")}`,
        timestamp: Date.now(),
      });

      for (const match of matchedCalcSkills) {
        const skill = match.skill;
        usedSkills.push(skill.id);

        sendEvent({
          type: "progress" as const,
          node: "analysis" as const,
          content: t("analysisNode.executingCompute", { skill: skill.manifest.title || skill.id }),
          skillId: skill.id,
          timestamp: Date.now(),
        });

        try {
          const result = await skillRuntime.execute(
            domainId!,
            skill.id,
            { query: analysisInstruction, data },
            {
              type: SkillType.EXECUTABLE,
              category: SkillCategory.ANALYSIS,
            }
          );
          computedResults[skill.id] = result.json || result.text || result.html;
          logger.info(tApp("queryFixed.16", { v0: (skill.id) }));

          sendEvent({
            type: "thinking" as const,
            node: "analysis" as const,
            content: `${t("analysisNode.computeDone")}: ${skill.manifest.title || skill.id}`,
            timestamp: Date.now(),
          });
        } catch (error) {
          const errMsg = error instanceof Error ? error.message : String(error);
          logger.warn(tApp("queryFixed.17", { v0: (skill.id) }), errMsg);
          sendEvent({
            type: "thinking" as const,
            node: "analysis" as const,
            content: t("analysisNode.executionFailed", {
              skill: skill.manifest.title || skill.id,
              error: errMsg,
            }),
            timestamp: Date.now(),
          });
        }
      }
    }

    sendEvent({
      type: "progress" as const,
      node: "analysis" as const,
      content: t("analysis.matchingMethodology"),
      timestamp: Date.now(),
    });

    const matchedKnowledgeSkills = registry
      ? await registry.matchKnowledgeSkillsWithLLM(
          analysisInstruction,
          SkillCategory.ANALYSIS,
          data,
          (event) => {
            sendEvent({
              type: "thinking" as const,
              node: "analysis" as const,
              content: event.content,
              timestamp: Date.now(),
            });
          }
        )
      : [];

    const selectedKnowledgeSkills =
      matchedKnowledgeSkills.length > 0
        ? matchedKnowledgeSkills.filter((m) => m.confidence >= 0.3)
        : registry
          ? registry
              .getSkills({ type: SkillType.KNOWLEDGE, category: SkillCategory.ANALYSIS })
              .map((s) => ({ skill: s, confidence: 0.1, reason: t("skill.default") }))
          : [];

    for (const match of selectedKnowledgeSkills) {
      usedSkills.push(match.skill.id);
    }

    const skillNames = selectedKnowledgeSkills.map((m) => m.skill.manifest.title || m.skill.id);
    if (skillNames.length > 0) {
      sendEvent({
        type: "thinking" as const,
        node: "analysis" as const,
        content: `${t("analysisNode.applyingMethod")}: ${skillNames.join(", ")}`,
        timestamp: Date.now(),
      });
    }

    let methodologyContent = "";
    for (const match of selectedKnowledgeSkills) {
      // Check whether the skill object is valid
      if (!match || !match.skill) {
        logger.warn(tApp("queryFixed.18", { v0: (JSON.stringify(match)) }));
        continue;
      }

      logger.info(
        tApp("queryFixed.19", { v0: (match.skill.id), v1: (String(match.skill.skillMdPath)) })
      );

      const content = loadSkillContent(match.skill);
      if (content) {
        methodologyContent += `\n## ${match.skill.manifest.title || match.skill.id}\n\n${truncateText(content, 2500)}\n`;
      }
    }

    if (!methodologyContent && registry)
      methodologyContent = truncateText(registry.getSkillsForAgentContext("analysis"), 6000);

    sendEvent({
      type: "progress" as const,
      node: "analysis" as const,
      content: t("analysisNode.generatingAnswer"),
      skills: usedSkills,
      timestamp: Date.now(),
    });

    signal?.throwIfAborted();
    const report = await runReportHarness({
      threadId: state.threadId, requestSeq, question: state.userQuestion, instruction: analysisInstruction,
      evidence, methodology: `${methodologyContent}\n${stringifyWithLimit(computedResults, 5000)}`, locale, signal,
      onCheckpoint: (resumable) => sendEvent({ type: "report_checkpoint", requestSeq, resumable }),
      onProgress: (content) => sendEvent({ type: "progress", node: "analysis", content, timestamp: Date.now() }),
      collect: async (question, reportSignal) => {
        const sourceRef = `report-evidence:${randomUUID()}`;
        const identity = { threadId: state.threadId, requestSeq, sourceKind: "report_evidence", sourceRef };
        const execution = await executeBaseQuery({ queryQuestion: question,
          token: config?.configurable?.token || "", apiKey: config?.configurable?.apiKey || "",
          userId: config?.configurable?.user_id || state.userId, locale,
          enabledBranches: DEEP_ANALYSIS_QUERY_BRANCHES, signal: reportSignal, answerMode: "evidence",
        });
        reportSignal.throwIfAborted();
        const output = execution.winner ? execution.output : execution.abcFallback;
        const error = execution.winner ? undefined : execution.error.message;
        await upsertQueryRun(buildQueryRunInput({ identity, question, status: execution.winner ? "completed" : "failed", error,
          fact: { datasets: output?.datasets ?? [], fullContent: output?.content, error, abcCodes: output?.abcCodes, abcDsls: output?.abcDsls } }));
        return createReportEvidence({ queryRunId: buildQueryRunId(state.threadId, requestSeq, sourceRef), question,
          datasets: output?.datasets ?? [], completed: !!execution.winner, error, abcCodes: output?.abcCodes, abcDsls: output?.abcDsls });
      },
    });
    signal?.throwIfAborted();
    const analysisContent = formatReportResult(report, locale);
    sendEvent({ type: "token", node: "analysis", content: analysisContent, timestamp: Date.now() });

    logger.info(tApp("queryFixed.21", { v0: (analysisContent.length) }));

    sendEvent({
      type: "thinking" as const,
      node: "analysis" as const,
      content: t("analysisNode.answerGenerated"),
      skills: usedSkills,
      timestamp: Date.now(),
    });

    const allSkillNames = [...matchedCalcSkills, ...selectedKnowledgeSkills].map(
      (m) => m.skill.manifest.title || m.skill.id
    );
    const thinkingContent =
      allSkillNames.length > 0
        ? `${t("analysisNode.applyingMethod")}: ${allSkillNames.join(", ")}\n${t("analysisNode.answerGenerated")}`
        : t("analysisNode.analysisComplete");

    logger.debug(tApp("queryFixed.30"));

    const analysisResult = { thinking: thinkingContent, result: analysisContent };
    if (queryArtifactCache) {
      const analysisArtifactRef = createRuntimeArtifactRef("analysis", state.threadId, requestSeq);
      queryArtifactCache.setAnalysisResult(analysisArtifactRef, analysisResult);
      return {
        analysisArtifactRef,
        events: [],
      };
    }

    logger.warn(tApp("queryFixed.31"), {
      threadId: state.threadId,
      requestSeq,
    });
    return {
      events: [],
    };
  } catch (error) {
    signal?.throwIfAborted();
    const errorMsg = error instanceof Error ? error.message : String(error);
    logger.error(tApp("queryFixed.22", { v0: (errorMsg) }));

    const thinkingEvent = {
      type: "thinking" as const,
      node: "analysis" as const,
      content: t("analysisNode.analysisFailed", { error: errorMsg }),
      skills: usedSkills,
      timestamp: Date.now(),
    };

    const errorEvent = {
      type: "error" as const,
      node: "analysis" as const,
      content: errorMsg,
      skills: usedSkills,
      timestamp: Date.now(),
    };

    sendEvent(thinkingEvent);
    sendEvent(errorEvent);

    return {
      events: [thinkingEvent, errorEvent],
      errors: [{ node: "analysis", message: errorMsg, timestamp: Date.now() }],
    };
  }
}
