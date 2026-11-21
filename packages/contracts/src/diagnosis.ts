import type { AgentChatMessage } from "./agent-chat";

export type SessionContextPage = "observe" | "autotest" | "general";

export interface SessionContext {
  /** Page/module the user was looking at when the diagnosis session was created. */
  page: SessionContextPage;
  /** Target key: observe -> turnKey; autotest case artifact -> runId/caseId. */
  targetKey?: string;
}

export interface SessionInfo {
  id: string;
  title: string;
  createdAt: string;
  context?: SessionContext;
  responseStatus: "idle" | "running";
}

export interface SessionHistorySnapshot {
  messages: AgentChatMessage[];
  responseStatus: "idle" | "running" | "completed" | "failed" | "cancelled" | "interrupted";
}

export type KnowledgeNodeType = "directory" | "file";

export interface KnowledgeTreeNode {
  name: string;
  path: string;
  type: KnowledgeNodeType;
  hasIndex?: boolean;
  indexPath?: string;
  children?: KnowledgeTreeNode[];
}

export interface KnowledgeMarkdownFile {
  path: string;
  title: string;
  content: string;
  updatedAt: string;
}

export interface KnowledgeTreeResponse {
  rootPath: string;
  indexPath: string;
  tree: KnowledgeTreeNode;
}

export interface SessionStopResult {
  cancelled: boolean;
}
