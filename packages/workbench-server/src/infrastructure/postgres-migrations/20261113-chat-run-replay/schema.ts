import { qualifiedTable } from "../../postgres";
import type { QueryableClient } from "../helpers";

export async function ensureChatRunReplayGuard(client: QueryableClient): Promise<void> {
  await client.query(`ALTER TABLE ${qualifiedTable("turn_runs")} ADD COLUMN IF NOT EXISTS client_run_id TEXT`);
  await client.query(`CREATE UNIQUE INDEX IF NOT EXISTS idx_turn_runs_client_run_id
    ON ${qualifiedTable("turn_runs")} (client_run_id) WHERE client_run_id IS NOT NULL`);
}
