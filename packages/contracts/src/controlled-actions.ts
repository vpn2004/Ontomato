/** The Manager receives the server's allowlist; the host never supplies backend roles or version conditions. */
export interface ControlledActionDefinition {
  id: string;
  name: string;
  className: string;
  field: string;
  fieldLabel: string;
  values: string[];
}
export interface ControlledActionDecision {
  id: string;
  actionId: string;
  actionName: string;
  objectId: string;
  before: Record<string, unknown>;
  values: Record<string, string>;
  expectedVersion: number;
  subject: string;
  identityMode: "shared-application-account";
  state: "PREPARED" | "EXECUTING" | "SUCCEEDED" | "CONFLICT" | "REJECTED" | "UNKNOWN";
  result?: { code?: string; version?: number; httpStatus?: number };
  createdAt: number;
  updatedAt: number;
  attempts: number;
}
export interface ControlledActionCatalog {
  enabled: boolean;
  subject?: string;
  actions: ControlledActionDefinition[];
  history: ControlledActionDecision[];
}
