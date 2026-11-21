import { describe, expect, it } from "vitest";
import { toChatHistory, toResponseEvent } from "../chat-view";
import type { AgentMessage, AgentToolResult } from "../types";

const args = { offset: 0, query: "date" };
const result: AgentToolResult = { content: [{ type: "text", text: "rule" }], terminate: true };
const messages: AgentMessage[] = [
  { role: "user", content: [{ type: "text", text: "Check" }] },
  {
    role: "assistant",
    content: [
      { type: "text", text: "Reading" },
      { type: "toolCall", id: "c1", name: "read_knowledge", arguments: args },
    ],
  },
  {
    role: "toolResult",
    toolCallId: "c1",
    toolName: "read_knowledge",
    content: result.content,
    terminate: true,
    isError: true,
  },
];

describe("chat view of one tool call", () => {
  it("shows the same identity, arguments, result and error live and in history", () => {
    const start = toResponseEvent({
      type: "tool_execution_start",
      toolCallId: "c1",
      toolName: "read_knowledge",
      args,
    });
    const end = toResponseEvent({
      type: "tool_execution_end",
      toolCallId: "c1",
      toolName: "read_knowledge",
      result,
      isError: true,
    });
    const segment = toChatHistory(messages)[1]!.segments!.find((item) => item.type === "tool_call");

    expect(start).toMatchObject({ type: "tool_start", toolCallId: "c1", tool: "read_knowledge" });
    expect(end?.type).toBe("tool_end");
    if (start?.type !== "tool_start" || end?.type !== "tool_end") return;
    // The live card formats args the same way the shared browser reducer does.
    expect(segment).toEqual({
      type: "tool_call",
      toolCallId: start.toolCallId,
      tool: start.tool,
      args: JSON.stringify(start.args, null, 2),
      result: end.result,
      isError: end.isError,
      status: "done",
    });
  });
});
