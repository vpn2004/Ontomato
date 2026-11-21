import type { PptSourceSection } from "@ontomato/contracts/ppt";
import { getCheckpointer } from "../../../../infrastructure/connection";
import { skillRuntime } from "../../../../core/skills";
import { SkillCategory, SkillType } from "../../../../core/skills/constants";
import { listChatRenderSnapshots } from "../../chat-snapshot-store";
import { getPrimaryQueryRun } from "../../../data-query/query-run-store";
import { extractDatasetsFromQueryRun } from "../../../data-query/query-run-artifacts";
import { combineResultTruncation } from "../../../data-query/protocol";
import { generatePpt } from "../../../analysis-agent/delivery/ppt-generator";
import { pptArtifactStore } from "../../../analysis-agent/delivery/ppt-artifacts";
import type { GraphState } from "../state";

export async function generateQueryConversationPpt(state: GraphState, config?: any) {
  const domainId = config?.configurable?.domainId;
  if (!domainId || !state.userId) throw new Error("PPT generation requires an authenticated domain and user");
  const signal = AbortSignal.any([AbortSignal.timeout(590000), ...(config?.signal ? [config.signal] : [])]);
  let file: {url: string; fileName: string; warnings: string[]} | undefined;
  // The built-in query agent uses the same enabled Skill runtime as custom agents.
  await skillRuntime.execute(domainId, "generate-ppt", {services: {generatePpt: async () => {
    const checkpointer = getCheckpointer();
    if (!checkpointer) throw new Error("Conversation history is unavailable");
    const messages = await checkpointer.getThreadMessages(state.threadId);
    const snapshots = await listChatRenderSnapshots(state.threadId);
    const sections: PptSourceSection[] = [];
    const warnings: string[] = [];
    for (const message of messages) {
      if (message.role === "user" || message.role === "assistant") {
        sections.push({id: `message-${sections.length}`, content: `${message.role}: ${message.content}`});
      }
    }
    for (const {requestSeq, snapshot} of snapshots.sort((a, b) => a.requestSeq - b.requestSeq)) {
      if (requestSeq >= (state.requestSeq ?? config?.configurable?.requestSeq ?? Infinity)) continue;
      const section: PptSourceSection = {id: `query-turn-${requestSeq}`, content: [snapshot.primaryText, snapshot.analysisText,
        snapshot.visualizationHTML ? `\n\`\`\`html\n${snapshot.visualizationHTML}\n\`\`\`` : ""].filter(Boolean).join("\n\n")};
      try {
        const run = await getPrimaryQueryRun(state.threadId, requestSeq);
        const datasets = run ? extractDatasetsFromQueryRun(run) : [];
        Object.assign(section, combineResultTruncation(datasets));
        if (run) section.tables = datasets.map(dataset => {
          const columns = [...new Set(dataset.rows.flatMap(row => Object.keys(row)))];
          section.content += `\n\n${dataset.title}\n${JSON.stringify(dataset.rows)}`;
          return {columns, rows: dataset.rows.map(row => columns.map(column => row[column] ?? ""))};
        });
      } catch { warnings.push(`Query data unavailable for turn ${requestSeq}`); }
      sections.push(section);
    }
    if (!sections.length) throw new Error("No conversation content is available for PPT generation");
    const title = String(messages.find(message => message.role === "user")?.content || state.userQuestion).slice(0, 80);
    const result = await generatePpt(title, sections, {}, signal);
    signal.throwIfAborted();
    file = {...await pptArtifactStore().save({domainId, userId: state.userId}, title, result.buffer), warnings: [...warnings, ...result.warnings]};
    return file;
  }}}, {type: SkillType.EXECUTABLE, category: SkillCategory.ANALYSIS}, {signal, maxRetries: 0});
  if (!file) throw new Error("The PPT Skill did not generate a file");
  return file;
}
