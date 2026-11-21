import { qualifiedTable } from "../../postgres";
import type { QueryableClient } from "../helpers";

export async function ensureChatReportEvidence(client: QueryableClient): Promise<void> {
  await client.query(`CREATE TABLE IF NOT EXISTS ${qualifiedTable("chat_report_runs")} (
    session_id TEXT PRIMARY KEY REFERENCES ${qualifiedTable("harness_sessions")}(id) ON DELETE CASCADE,
    thread_id TEXT NOT NULL REFERENCES ${qualifiedTable("thread_metadata")}(thread_id) ON DELETE CASCADE,
    request_seq INTEGER NOT NULL,
    contract JSONB NOT NULL,
    evidence JSONB NOT NULL,
    statistics JSONB NOT NULL DEFAULT '[]'::jsonb,
    phase TEXT NOT NULL DEFAULT 'collecting',
    report JSONB,
    review JSONB,
    error TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(thread_id, request_seq)
  )`);
}
