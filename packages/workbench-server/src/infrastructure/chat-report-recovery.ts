import { qualifiedTable } from "./postgres";
import type { QueryableClient } from "./postgres-migrations/helpers";

/** Startup only: a process restart cannot leave a saved report's presentation running forever. */
export async function recoverInterruptedReports(client: QueryableClient): Promise<void> {
  try {
    await client.query("BEGIN");
    await client.query(`UPDATE ${qualifiedTable("chat_report_runs")} r SET phase='interrupted', updated_at=NOW()
      FROM ${qualifiedTable("turn_runs")} t WHERE t.thread_id=r.thread_id AND t.request_seq=r.request_seq AND t.status='running'`);
    await client.query(`UPDATE ${qualifiedTable("turn_runs")} t SET status='failed', ended_at=NOW(), updated_at=NOW()
      FROM ${qualifiedTable("chat_report_runs")} r WHERE t.thread_id=r.thread_id AND t.request_seq=r.request_seq
      AND t.status='running' AND r.phase='interrupted'`);
    await client.query("COMMIT");
  } catch (error) { await client.query("ROLLBACK"); throw error; }
}
