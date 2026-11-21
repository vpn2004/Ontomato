import type { AgentResponseEvent } from "@ontomato/contracts/agent-chat";
import type { AgentEvent } from "../../../../core/agent-loop/index";
import { toResponseEvent } from "../../../../core/agent-loop/chat-view";

export const DISPATCH_AGENT_TOOL = "dispatch_agent";
export const PUBLIC_SUBAGENT_TOOL = "subagent";

/** Convert one Agent event into zero or one user-visible diagnosis stream event. */
export function toDiagnosisStreamEvent(event: AgentEvent): AgentResponseEvent | undefined {
  const streamEvent = toResponseEvent(event);
  if (
    (streamEvent?.type !== "tool_start" && streamEvent?.type !== "tool_end") ||
    streamEvent.tool !== DISPATCH_AGENT_TOOL
  )
    return streamEvent;
  return streamEvent.type === "tool_start"
    ? { ...streamEvent, tool: PUBLIC_SUBAGENT_TOOL, args: {} }
    : {
        ...streamEvent,
        tool: PUBLIC_SUBAGENT_TOOL,
        result: { status: streamEvent.isError ? "failed" : "completed" },
      };
}
