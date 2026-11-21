import { getCheckpointer } from "../../../infrastructure/connection";
import { HttpError } from "../../../utils/errors";
import { upsertChatRenderSnapshot } from "../chat-snapshot-store";
import { formatReportResult, runReportHarness } from "./harness";
import { readReportCheckpoint } from "./store";

/** Explicit user retry of report work only; no query planner, database query, or Action replay. */
export async function resumeSavedReport(input: {
  threadId: string; sourceRequestSeq: number; requestSeq: number; userId: string; domainId: string;
  locale?: string; signal: AbortSignal; onProgress: (content: string) => void;
  onCheckpoint?: (resumable: boolean) => void;
}): Promise<string> {
  input.signal.throwIfAborted();
  const checkpointer = getCheckpointer();
  if (!checkpointer) throw new HttpError(503, "Checkpoint service unavailable");
  await checkpointer.verifyThreadAccess(input.threadId, input.userId, input.domainId);
  const saved = await readReportCheckpoint(input.threadId, input.sourceRequestSeq);
  if (!saved) throw new HttpError(404, "Saved report evidence not found");
  const result = await runReportHarness({
    threadId: input.threadId, requestSeq: input.requestSeq,
    question: saved.contract.originalQuestion, instruction: saved.contract.instruction,
    evidence: saved.evidence, seed: { statistics: saved.statistics, report: saved.report, review: saved.review },
    locale: input.locale, signal: input.signal, onProgress: input.onProgress, onCheckpoint: input.onCheckpoint,
  });
  input.signal.throwIfAborted();
  const notice = input.locale?.startsWith("zh")
    ? "本报告复用原查询保存的证据，未重新读取业务数据；数据状态以原查询时点为准。"
    : "This report reuses saved query evidence. Business data was not queried again; facts reflect the original query time.";
  const content = `> ${notice}\n\n${formatReportResult(result, input.locale)}`;
  await upsertChatRenderSnapshot({ threadId: input.threadId, requestSeq: input.requestSeq,
    snapshot: { mode: "standard", status: "completed", primaryText: "", analysisText: content }, source: "report-resume" });
  return content;
}
