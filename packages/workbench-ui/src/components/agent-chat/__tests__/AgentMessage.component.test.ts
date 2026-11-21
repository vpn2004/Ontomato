import { mount } from "@vue/test-utils";
import { createI18n } from "vue-i18n";
import { describe, expect, it } from "vitest";
import type { AgentChatMessage } from "@ontomato/contracts/agent-chat";
import AgentMessage from "../AgentMessage.vue";
import en from "../../../locales/en";

const tool = (id: string, extra: object = {}) => ({
  type: "tool_call" as const,
  toolCallId: id,
  tool: `read_${id}`,
  args: `{"id":"${id}"}`,
  status: "done" as const,
  ...extra,
});

function render(message: AgentChatMessage) {
  return mount(AgentMessage, {
    props: { message },
    global: { plugins: [createI18n({ legacy: false, locale: "en", messages: { en } })] },
  });
}

describe("agent message work steps", () => {
  it("collapses a run of tool calls into one line that still names the running call and failures", async () => {
    const wrapper = render({
      role: "assistant",
      content: "",
      segments: [
        { type: "text", content: "Looking at the rules." },
        tool("a"),
        { type: "text", content: "  " },
        tool("b", { isError: true, result: "denied" }),
        tool("c", { status: "running" }),
        { type: "text", content: "Here is what I found." },
        tool("d"),
      ],
    });
    const [group, single] = wrapper.findAll(".agent-step");
    expect(group!.text()).toContain("3 tool calls");
    expect(group!.text()).toContain("read_c");
    expect(group!.text()).toContain("1 failed");
    expect(group!.findAll(".agent-tool")).toHaveLength(0);
    // A lone call is its own line; the text between runs keeps its place.
    expect(single!.get(".agent-tool__name").text()).toBe("read_d");
    expect(wrapper.text().indexOf("Here is what I found.")).toBeLessThan(
      wrapper.text().indexOf("read_d")
    );

    await group!.get(".agent-step__row").trigger("click");
    expect(group!.findAll(".agent-tool")).toHaveLength(3);
    await group!.findAll(".agent-tool__header")[1]!.trigger("click");
    expect(group!.get(".agent-tool__detail").text()).toContain("denied");
  });
});
