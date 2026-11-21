import { getCheckpointer } from "../../../infrastructure/connection";
import { HttpError } from "../../../utils/errors";
import { readReportCheckpoint } from "./store";

export async function getReportEvidenceDownload(threadId: string, requestSeq: number, userId: string, domainId: string) {
  if (!Number.isSafeInteger(requestSeq) || requestSeq < 0) throw new HttpError(400, "Invalid report request sequence");
  const checkpointer = getCheckpointer();
  if (!checkpointer) throw new HttpError(503, "Checkpoint service unavailable");
  await checkpointer.verifyThreadAccess(threadId, userId, domainId);
  const saved = await readReportCheckpoint(threadId, requestSeq);
  if (!saved) throw new HttpError(404, "Saved report evidence not found");
  return {
    fileName: `report-evidence-${requestSeq}.json`, contentType: "application/json; charset=utf-8",
    body: JSON.stringify({ formatVersion: 1, phase: saved.phase, contract: saved.contract, evidence: saved.evidence,
      statistics: saved.statistics, report: saved.report, review: saved.review }, null, 2),
  };
}
