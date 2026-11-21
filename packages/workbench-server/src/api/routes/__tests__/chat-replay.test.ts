import express from "express";
import http from "node:http";
import type { AddressInfo } from "node:net";
import { afterEach, expect, it, vi } from "vitest";
import { installWorkbenchIdentity } from "../../../identity/installed";
import type { WorkbenchIdentity } from "../../../identity/types";

const io = vi.hoisted(() => ({ start: vi.fn(async () => 0), workflow: vi.fn(async () => ({})) }));
vi.mock("../../../services/chat/graph/workflow", () => ({ runDataAgentWorkflow: io.workflow }));
vi.mock("../../../infrastructure/connection", () => ({ initializeConnection: async () => {}, getCheckpointer: () => undefined }));
vi.mock("../../../services/chat/thread-store", () => ({ startStandardTurn: io.start }));
vi.mock("../../../services/turn-run/turn-run-store", async (original) => ({
  ...await original<typeof import("../../../services/turn-run/turn-run-store")>(),
  recordTurnAnswerCompletion: vi.fn(), recordTurnRunFinish: vi.fn(),
}));
vi.mock("../../utils/title", () => ({ generateTitleAsync: async () => undefined }));
vi.mock("../../../i18n", () => ({ setLocale: () => {}, t: (key: string) => key, tApp: (key: string) => key }));
import router from "../chat";
import { ReplayedChatRunError } from "../../../services/turn-run/turn-run-store";
let server: http.Server;
afterEach(async () => { vi.clearAllMocks(); if (server) await new Promise<void>((resolve) => server.close(() => resolve())); });

it.each([true, false])("does not replay a run lost on restart (browser cursor: %s)", async (withCursor) => {
  if (!withCursor) io.start.mockRejectedValueOnce(new ReplayedChatRunError());
  installWorkbenchIdentity({ requireDomainId: (identity: { domainId: string }) => identity.domainId } as WorkbenchIdentity);
  const app = express();
  app.use((req, _res, next) => { Object.assign(req, { userId: "user", domainId: "domain" }); next(); });
  app.use("/chat", router);
  server = await new Promise<http.Server>((resolve) => { const s = app.listen(0, "127.0.0.1", () => resolve(s)); });
  const response = await fetch(`http://127.0.0.1:${(server.address() as AddressInfo).port}/chat?message=report&threadId=replay-thread&runId=lost-run-${withCursor}`, {
    headers: withCursor ? { "Last-Event-ID": "37" } : {},
  });
  expect(await response.text()).toContain('"type":"run_not_found"');
  if (withCursor) expect(io.start).not.toHaveBeenCalled();
  expect(io.workflow).not.toHaveBeenCalled();
});
