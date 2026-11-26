import { getPostgresPool, qualifiedTable } from "../../../infrastructure/postgres";
import type { ReportContract, ReportSubmission } from "./contract";
import { evidenceHash, type EvidenceStatistic, type ReportEvidence } from "./evidence";
import type { ReportVerificationMemory } from "./review-memory";

export interface ReportCheckpoint {
  contract: ReportContract;
  evidence: ReportEvidence;
  statistics: EvidenceStatistic[];
  phase: string;
  report?: ReportSubmission;
  review?: { passed: boolean; issues: string[]; memory?: ReportVerificationMemory };
  error?: string;
}

export async function readReportCheckpoint(threadId: string, requestSeq: number): Promise<ReportCheckpoint | undefined> {
  const result = await getPostgresPool().query(`SELECT contract, evidence, statistics, phase, report, review, error
    FROM ${qualifiedTable("chat_report_runs")} WHERE thread_id=$1 AND request_seq=$2`, [threadId, requestSeq]);
  return result.rows[0] as ReportCheckpoint | undefined;
}

export async function listReportStates(threadId: string): Promise<Array<{ requestSeq: number; resumable: boolean }>> {
  const result = await getPostgresPool().query(`SELECT r.request_seq, (r.phase <> 'completed' AND s.status='idle') AS resumable
    FROM ${qualifiedTable("chat_report_runs")} r
    JOIN ${qualifiedTable("harness_sessions")} s ON s.id=r.session_id
    WHERE r.thread_id=$1 ORDER BY r.request_seq`, [threadId]);
  return result.rows.map((row) => ({ requestSeq: Number(row.request_seq), resumable: row.resumable === true }));
}

export async function listRecoverableReports(threadId: string): Promise<number[]> {
  return (await listReportStates(threadId)).filter((row) => row.resumable).map((row) => row.requestSeq);
}

export async function deleteReportSessions(threadId: string): Promise<void> {
  await getPostgresPool().query(`DELETE FROM ${qualifiedTable("harness_sessions")} WHERE id IN (
    SELECT session_id FROM ${qualifiedTable("chat_report_runs")} WHERE thread_id=$1)`, [threadId]);
}


export async function openReportCheckpoint(threadId: string, requestSeq: number, contract: ReportContract, evidence: ReportEvidence) {
  const sessionId = `chat-report:${evidenceHash([threadId, requestSeq])}`;
  const client = await getPostgresPool().connect();
  try {
    await client.query("BEGIN");
    await client.query(`INSERT INTO ${qualifiedTable("harness_sessions")} (id, status) VALUES ($1, 'idle') ON CONFLICT DO NOTHING`, [sessionId]);
    await client.query(`INSERT INTO ${qualifiedTable("chat_report_runs")} (session_id, thread_id, request_seq, contract, evidence)
      VALUES ($1, $2, $3, $4::jsonb, $5::jsonb) ON CONFLICT DO NOTHING`, [sessionId, threadId, requestSeq, JSON.stringify(contract), JSON.stringify(evidence)]);
    const result = await client.query(`SELECT * FROM ${qualifiedTable("chat_report_runs")} WHERE session_id = $1`, [sessionId]);
    const checkpoint = result.rows[0] as ReportCheckpoint;
    if (checkpoint.contract.version !== contract.version) throw new Error("Report requirement version changed; start a new turn.");
    await client.query("COMMIT");
    return { sessionId, checkpoint };
  } catch (error) {
    await client.query("ROLLBACK"); throw error;
  } finally { client.release(); }
}

/** Serialize with Harness finish/begin so a cancelled or replaced attempt cannot write. */
export async function saveReportCheckpoint(sessionId: string, turnId: string, checkpoint: ReportCheckpoint): Promise<void> {
  const client = await getPostgresPool().connect();
  try {
    await client.query("BEGIN");
    const active = await client.query(`SELECT status, active_turn_id FROM ${qualifiedTable("harness_sessions")} WHERE id = $1 FOR UPDATE`, [sessionId]);
    if (active.rows[0]?.status !== "running" || active.rows[0]?.active_turn_id !== turnId) throw new Error("Report attempt is no longer active.");
    await client.query(`UPDATE ${qualifiedTable("chat_report_runs")} SET evidence=$2::jsonb, statistics=$3::jsonb,
      phase=$4, report=$5::jsonb, review=$6::jsonb, error=$7, updated_at=NOW() WHERE session_id=$1`,
    [sessionId, JSON.stringify(checkpoint.evidence), JSON.stringify(checkpoint.statistics), checkpoint.phase,
      JSON.stringify(checkpoint.report ?? null), JSON.stringify(checkpoint.review ?? null), checkpoint.error ?? null]);
    await client.query("COMMIT");
  } catch (error) { await client.query("ROLLBACK"); throw error; }
  finally { client.release(); }
}
