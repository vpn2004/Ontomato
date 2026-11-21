import { describe, expect, it } from "vitest";
import type { AgentMessage } from "../../../../../core/agent-loop/types";
import { messagesToSessionHistory } from "../session-history-view";
import { toDiagnosisStreamEvent } from "../sse-bridge";

const childArgs = { role: "sql", task: "private child instructions" };
const childResult = { content: [{ type: "text" as const, text: "private child transcript" }] };

describe("diagnosis subagent view", () => {
  it("hides child-agent arguments and results in both the live stream and history", () => {
    const live = [
      toDiagnosisStreamEvent({
        type: "tool_execution_start",
        toolCallId: "c1",
        toolName: "dispatch_agent",
        args: childArgs,
      }),
      toDiagnosisStreamEvent({
        type: "tool_execution_end",
        toolCallId: "c1",
        toolName: "dispatch_agent",
        result: childResult,
        isError: false,
      }),
    ];
    const history = messagesToSessionHistory([
      {
        role: "assistant",
        content: [{ type: "toolCall", id: "c1", name: "dispatch_agent", arguments: childArgs }],
      },
      {
        role: "toolResult",
        toolCallId: "c1",
        toolName: "dispatch_agent",
        content: childResult.content,
        isError: false,
      },
    ] satisfies AgentMessage[]);

    expect(live).toEqual([
      { type: "tool_start", toolCallId: "c1", tool: "subagent", args: {} },
      {
        type: "tool_end",
        toolCallId: "c1",
        tool: "subagent",
        result: { status: "completed" },
        isError: false,
      },
    ]);
    expect(history[0]!.segments).toEqual([
      {
        type: "tool_call",
        toolCallId: "c1",
        tool: "subagent",
        args: "",
        result: { status: "completed" },
        isError: false,
        status: "done",
      },
    ]);
  });
});
