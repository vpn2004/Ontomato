import type {
  AgentChatMessage,
  AgentChatSegment,
  AgentChatToolSegment,
  AgentResponseEvent,
} from "@ontomato/contracts/agent-chat";
import type {
  AgentEvent,
  AgentMessage,
  AgentToolResult,
  AssistantMessage,
  ToolResultMessage,
} from "./types";

/**
 * Tool result as shown in chat. History and the live tail both use this shape,
 * so a settled transcript shows the same result the live card showed.
 */
function chatToolResult(result: Pick<AgentToolResult, "content" | "terminate">): unknown {
  return {
    content: result.content,
    ...(result.terminate != null ? { terminate: result.terminate } : {}),
  };
}

/** Project raw Harness messages into chat messages; tool results join their calling assistant. */
export function toChatHistory(messages: readonly AgentMessage[]): AgentChatMessage[] {
  const history: AgentChatMessage[] = [];
  let lastAssistant: AgentChatMessage | undefined;

  for (const msg of messages) {
    if (msg.role === "user") {
      history.push({ role: "user", content: extractText(msg.content) });
      lastAssistant = undefined;
      continue;
    }

    if (msg.role === "assistant") {
      const assistant = assistantToChatMessage(msg.content);
      history.push(assistant);
      lastAssistant = assistant;
      continue;
    }

    if (!lastAssistant) {
      lastAssistant = { role: "assistant", content: "", segments: [] };
      history.push(lastAssistant);
    }
    attachToolResult(lastAssistant, msg);
  }

  return history;
}

function assistantToChatMessage(content: AssistantMessage["content"]): AgentChatMessage {
  const segments: AgentChatSegment[] = [];

  for (const block of content) {
    if (block.type === "text") {
      segments.push({ type: "text", content: block.text });
    } else if (block.type === "thinking") {
      segments.push({ type: "thinking", content: block.text });
    } else if (block.type === "toolCall") {
      segments.push({
        type: "tool_call",
        toolCallId: block.id,
        tool: block.name,
        args: stringifyToolArgs(block.arguments),
        status: "running",
      });
    }
  }

  const contentText = content
    .flatMap((block) => (block.type === "text" ? [block.text] : []))
    .join("");

  return {
    role: "assistant",
    content: contentText,
    ...(segments.length > 0 ? { segments } : {}),
  };
}

function attachToolResult(assistant: AgentChatMessage, msg: ToolResultMessage): void {
  if (!assistant.segments) assistant.segments = [];

  let seg = assistant.segments.find(
    (item): item is AgentChatToolSegment =>
      item.type === "tool_call" && item.toolCallId === msg.toolCallId
  );
  if (!seg) {
    seg = {
      type: "tool_call",
      toolCallId: msg.toolCallId,
      tool: msg.toolName,
      args: "",
      status: "running",
    };
    assistant.segments.push(seg);
  }

  seg.result = chatToolResult(msg);
  seg.isError = msg.isError;
  seg.status = "done";
}

function stringifyToolArgs(args: Record<string, unknown>): string {
  try {
    return JSON.stringify(args ?? {}, null, 2);
  } catch {
    return String(args);
  }
}

function extractText(content: unknown): string {
  if (typeof content === "string") return content;
  if (!Array.isArray(content)) return "";
  return content
    .filter((c: { type: string }) => c.type === "text")
    .map((c: { text?: string }) => c.text ?? "")
    .join("");
}

/** Convert one Agent event into zero or one live response event. */
export function toResponseEvent(event: AgentEvent): AgentResponseEvent | undefined {
  switch (event.type) {
    case "message_update": {
      const update = event.assistantMessageEvent;
      if (update.type === "text_delta") {
        return update.delta ? { type: "token", content: update.delta } : undefined;
      }
      if (update.type === "thinking_delta") {
        return update.delta ? { type: "thinking", content: update.delta } : undefined;
      }
      return undefined;
    }

    case "tool_execution_start":
      return {
        type: "tool_start",
        toolCallId: event.toolCallId,
        tool: event.toolName,
        args:
          event.args && typeof event.args === "object" && !Array.isArray(event.args)
            ? (event.args as Record<string, unknown>)
            : {},
      };

    case "tool_execution_end":
      return {
        type: "tool_end",
        toolCallId: event.toolCallId,
        tool: event.toolName,
        result: chatToolResult(event.result),
        isError: event.isError,
      };

    default:
      return undefined;
  }
}
