import type { PptSourceSection } from "@ontomato/contracts/ppt";
import type { AnalysisConversationTurnRecord } from "../task/conversation-types";
import type { AgentMessage } from "../../../core/agent-loop/types";
import type { LoopSkillEvidenceRecord } from "../loop/skills";
import { combineResultTruncation } from "../../data-query/protocol";

export type PptConversationTurn = Pick<AnalysisConversationTurnRecord, "requestSeq" | "userMessage" | "evidenceRefs" | "charts"> & {
  messages?: AgentMessage[];
  finalAnswer?: string;
};

export function collectPptConversation(
  turns: readonly PptConversationTurn[],
  resolveEvidence: (id: string) => LoopSkillEvidenceRecord | undefined
): { sections: PptSourceSection[]; warnings: string[] } {
  const sections: PptSourceSection[] = [];
  const warnings: string[] = [];
  const evidenceIds = new Set<string>();
  for (const turn of [...turns].sort((a, b) => a.requestSeq - b.requestSeq)) {
    const texts = (turn.messages || []).filter(message => message.role === "assistant")
      .flatMap(message => message.content.filter(block => block.type === "text").map(block => block.text));
    if (turn.finalAnswer) texts.push(turn.finalAnswer);
    sections.push({id: `turn-${turn.requestSeq}`, heading: turn.userMessage.slice(0, 150),
      content: [turn.userMessage, ...new Set(texts)].join("\n\n")});
    for (const ref of turn.evidenceRefs) {
      if (evidenceIds.has(ref.questionId)) continue;
      evidenceIds.add(ref.questionId);
      const evidence = resolveEvidence(ref.questionId);
      if (!evidence) { warnings.push(`Query result is unavailable: ${ref.question}`); continue; }
      for (const entry of evidence.entries) {
        const columns = [...new Set(entry.data.flatMap(row => Object.keys(row)))];
        const table = { columns, rows: entry.data.map(row => columns.map(column => row[column])) };
        sections.push({id: `query-${ref.questionId}-${entry.index}`, heading: entry.title || evidence.question,
          content: [evidence.question, entry.title, entry.sourceWindows?.length ? `Source window ranges: ${JSON.stringify(entry.sourceWindows)}. Calculations describe these ranges.` : "", JSON.stringify(table)].filter(Boolean).join("\n"), tables: [table],
          ...combineResultTruncation([entry])});
      }
    }
    for (const chart of turn.charts) {
      sections.push({id: `chart-${turn.requestSeq}-${chart.chartId}`, heading: chart.title,
        content: [chart.title, chart.sourceSubQuestion, chart.reason, chart.evidenceSummary, `\`\`\`html\n${chart.html}\n\`\`\``].filter(Boolean).join("\n\n"),
        ...combineResultTruncation([chart])});
    }
  }
  return {sections, warnings};
}
