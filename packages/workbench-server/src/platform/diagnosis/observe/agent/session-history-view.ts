import type { AgentChatMessage, AgentChatSegment } from "@ontomato/contracts/agent-chat";
import { toChatHistory } from "../../../../core/agent-loop/chat-view";
import type { AgentMessage } from "../../../../core/agent-loop/index";
import { DISPATCH_AGENT_TOOL, PUBLIC_SUBAGENT_TOOL } from "./sse-bridge";

/** Child-agent internals stay hidden: only its public name and outcome are shown. */
function maskSubagent(segment: AgentChatSegment): AgentChatSegment {
  if (segment.type !== "tool_call" || segment.tool !== DISPATCH_AGENT_TOOL) return segment;
  return {
    ...segment,
    tool: PUBLIC_SUBAGENT_TOOL,
    args: "",
    ...(segment.status === "done"
      ? { result: { status: segment.isError ? "failed" : "completed" } }
      : {}),
  };
}

export function messagesToSessionHistory(messages: AgentMessage[]): AgentChatMessage[] {
  return toChatHistory(messages).map((message) =>
    message.segments ? { ...message, segments: message.segments.map(maskSubagent) } : message
  );
}
