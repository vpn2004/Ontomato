import { beforeEach, expect, it, vi } from "vitest";
import type { GraphState } from "../../state";
const mocks = vi.hoisted(() => ({generate: vi.fn(), execute: vi.fn(), save: vi.fn(), partial: false}));
vi.mock("../../../../../infrastructure/connection", () => ({getCheckpointer: () => ({getThreadMessages: async () => [
  {role: "user", content: "EARLY QUESTION"}, {role: "assistant", content: "EARLY ANSWER"},
  ...Array.from({length: 20}, (_, index) => ({role: "user", content: `question ${index}`})),
]})}));
vi.mock("../../../../../core/skills", () => ({skillRuntime: {execute: mocks.execute}}));
vi.mock("../../../chat-snapshot-store", () => ({listChatRenderSnapshots: async () => [{requestSeq: 1, snapshot: {primaryText: "Result", analysisText: "Explanation", visualizationHTML: "<svg></svg>"}}]}));
vi.mock("../../../../data-query/query-run-store", () => ({getPrimaryQueryRun: async () => ({})}));
vi.mock("../../../../data-query/query-run-artifacts", () => ({extractDatasetsFromQueryRun: () => [{title: "Sales", rows: [{amount: 42}],
  ...(mocks.partial ? {truncated: true, rowLimit: 100000, truncationHint: "Partial source"} : {})}]}));
vi.mock("../../../../analysis-agent/delivery/ppt-generator", () => ({generatePpt: mocks.generate}));
vi.mock("../../../../analysis-agent/delivery/ppt-artifacts", () => ({pptArtifactStore: () => ({save: mocks.save})}));
import { generateQueryConversationPpt } from "../conversation-ppt";
const state = {threadId: "thread", userId: "owner", requestSeq: 22, userQuestion: "Generate PPT"} as GraphState;
beforeEach(() => {
  vi.resetAllMocks();
  mocks.partial = false;
  mocks.execute.mockImplementation(async (_domain, _id, input) => ({json: await input.services.generatePpt()}));
  mocks.generate.mockResolvedValue({buffer: Buffer.from("pptx"), warnings: []});
  mocks.save.mockResolvedValue({url: "/api/ppt/files/test", fileName: "test.pptx"});
});
it("uses all persisted messages, chart explanations and native query tables through the Skill", async () => {
  const file = await generateQueryConversationPpt(state, {configurable: {domainId: "domain"}});
  const sections = mocks.generate.mock.calls[0][1];
  expect(JSON.stringify(sections)).toContain("EARLY ANSWER");
  expect(JSON.stringify(sections)).toContain("question 19");
  expect(JSON.stringify(sections)).toContain("Explanation");
  expect(JSON.stringify(sections)).toContain("<svg>");
  expect(sections.at(-1).tables).toEqual([{columns: ["amount"], rows: [[42]]}]);
  expect(mocks.save.mock.calls[0][0]).toEqual({domainId: "domain", userId: "owner"});
  expect(file.url).toContain("/api/ppt/files/");
});
it("does not bypass a disabled Skill", async () => {
  mocks.execute.mockRejectedValue(new Error("Skill disabled"));
  await expect(generateQueryConversationPpt(state, {configurable: {domainId: "domain"}})).rejects.toThrow("disabled");
  expect(mocks.generate).not.toHaveBeenCalled();
});

it("keeps the saved dataset's partial marker when preparing conversation slides", async () => {
  mocks.partial = true;
  await generateQueryConversationPpt(state, {configurable: {domainId: "domain"}});
  const sections = mocks.generate.mock.calls[0][1];
  expect(sections.at(-1)).toMatchObject({truncated: true, rowLimit: 100000, truncationHint: "Partial source"});
});

