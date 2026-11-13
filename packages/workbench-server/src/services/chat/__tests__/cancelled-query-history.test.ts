import { beforeEach, expect, it, vi } from "vitest";
import { getThreadMessagesPayload } from "../thread-service";
const store = vi.hoisted(() => ({ status: "cancelled", snapshots: [] as unknown[], verify: vi.fn() }));
vi.mock("../../../infrastructure/connection", () => ({ getCheckpointer: () => ({ verifyThreadAccess: store.verify, getThreadMessages: async () => [{ role: "user", content: "Count", requestSeq: 0 }] }) }));
vi.mock("../chat-snapshot-store", () => ({ listChatRenderSnapshots: async () => store.snapshots }));
vi.mock("../execution-event-store", () => ({ listExecutionEvents: async () => [] }));
vi.mock("../../data-query/query-run-store", () => ({ listQueryRuns: async () => [{ requestSeq: 0, status: "failed", sourceKind: "query_stage", fullContent: "| Count |\n| --- |\n| 42 |\n<!-- query-dataset:one -->", datasetsPayload: [{ id: "one", data: [{ count: 42 }] }] }] }));
vi.mock("../../turn-run/turn-run-store", () => ({ listTurnRuns: async () => [{ requestSeq: 0, source: "chat", status: store.status, displayMessage: "Count" }], turnRunDurationMs: () => 1000 }));
vi.mock("../../../i18n", () => ({ t: (key: string) => key, tApp: (key: string) => key }));
beforeEach(() => { store.status = "cancelled"; store.snapshots = []; store.verify.mockReset(); });
it("restores the saved query when cancellation prevented the final response snapshot", async () => {
  const result = await getThreadMessagesPayload("thread", { userId: "user", domainId: "domain" } as never);
  expect(result.messages).toHaveLength(2);
  expect(result.messages[1]).toMatchObject({ role: "assistant", requestSeq: 0, snapshot: { stoppedByUser: true, primaryText: expect.stringContaining("42"), datasets: [expect.objectContaining({ rows: [{ count: 42 }] })] } });
  expect(store.verify).toHaveBeenCalledWith("thread", "user", "domain");
});
it("keeps active turns out of finalized history", async () => {
  store.status = "running";
  const result = await getThreadMessagesPayload("thread", { userId: "user", domainId: "domain" } as never);
  expect(result.messages.map(m => m.role)).toEqual(["user"]);
  expect(result.activeTurn?.requestSeq).toBe(0);
});
it("keeps an existing answer authoritative when stopped during quality checking", async () => {
  store.snapshots = [{ requestSeq: 0, snapshot: { mode: "standard", status: "completed", primaryText: "Final explanation" } }];
  const result = await getThreadMessagesPayload("thread", { userId: "user", domainId: "domain" } as never);
  expect(result.messages.filter(m => m.role === "assistant")).toHaveLength(1);
  expect(result.messages[1]?.snapshot).toMatchObject({ primaryText: "Final explanation", stoppedByUser: true });
});
it("enforces thread access before exposing saved partial data", async () => {
  store.verify.mockRejectedValue(new Error("Forbidden"));
  await expect(getThreadMessagesPayload("thread", { userId: "other", domainId: "domain" } as never)).rejects.toThrow("Forbidden");
});
