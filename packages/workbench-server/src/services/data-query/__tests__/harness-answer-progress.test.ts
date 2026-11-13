import { beforeEach, expect, it, vi } from "vitest";
import { polishHarnessAnswer } from "../branches/abc-harness-answer";
const mocks = vi.hoisted(() => ({ stream: vi.fn() }));
vi.mock("../../../config/model-factory", () => ({ createModel: async () => ({ stream: mocks.stream }) }));
vi.mock("../../../core/prompts/loader", () => ({ renderPrompt: () => "prompt" }));
vi.mock("../../../logging/model-agents", () => ({ modelAgentName: () => "test" }));
vi.mock("../../../i18n", () => ({ tForLocale: () => "test", tApp: () => "test" }));
const params = () => ({ originalQuestion: "count", fallbackContent: "scope ".repeat(100) + "<!-- query-dataset:one -->", harnessProcess: "", datasets: [], displayDataMessages: ["| Count |\n| --- |\n| 42 |\n\n<!-- query-dataset:one -->"], pushContent: vi.fn(), labels: { conclusion: "Conclusion", scope: "Scope", process: "Process", data: "Data" } });
beforeEach(() => { vi.clearAllMocks(); });
it("keeps ready tables visible in each streamed explanation prefix", async () => {
  mocks.stream.mockImplementation(async function* () { yield { content: "## Scope\n\nVerified records." }; yield { content: "\n\n<!-- DATA_TABLES -->\n\n## Conclusion\n\n42 records." }; });
  const input = params(); await polishHarnessAnswer(input);
  expect(input.pushContent).toHaveBeenCalled();
  for (const [content] of input.pushContent.mock.calls) expect(content.match(/query-dataset:one/g)).toHaveLength(1);
});
it("publishes an existing fallback once without replaying incomplete table fragments", async () => {
  mocks.stream.mockRejectedValue(new Error("model unavailable"));
  const input = params(); await polishHarnessAnswer(input);
  expect(input.pushContent).toHaveBeenCalledTimes(1);
  expect(input.pushContent).toHaveBeenCalledWith(input.fallbackContent, false);
});
