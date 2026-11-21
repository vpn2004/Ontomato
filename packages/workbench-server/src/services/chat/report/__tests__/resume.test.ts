import { beforeEach, describe, expect, it, vi } from "vitest";
const io = vi.hoisted(() => ({ access: vi.fn(), read: vi.fn(), run: vi.fn(), save: vi.fn() }));
vi.mock("../../../../infrastructure/connection", () => ({ getCheckpointer: () => ({ verifyThreadAccess: io.access }) }));
vi.mock("../store", () => ({ readReportCheckpoint: io.read }));
vi.mock("../harness", () => ({ runReportHarness: io.run, formatReportResult: (result: any) => result.markdown }));
vi.mock("../../chat-snapshot-store", () => ({ upsertChatRenderSnapshot: io.save }));
import { resumeSavedReport } from "../resume";
const input = { threadId: "thread", sourceRequestSeq: 0, requestSeq: 1, userId: "user", domainId: "domain", signal: new AbortController().signal, onProgress: vi.fn() };
beforeEach(() => {
  vi.resetAllMocks();
  io.read.mockResolvedValue({ contract: { originalQuestion: "Original full scope", instruction: "Report" }, evidence: { version: "immutable" } });
  io.run.mockResolvedValue({ markdown: "Checked report", status: "verified", issues: [] });
});
describe("explicit report recovery", () => {
  it("revalidates ownership and uses the original evidence without a collection tool", async () => {
    const content = await resumeSavedReport(input);
    expect(io.access).toHaveBeenCalledWith("thread", "user", "domain");
    expect(io.run).toHaveBeenCalledWith(expect.objectContaining({ question: "Original full scope", requestSeq: 1, evidence: { version: "immutable" } }));
    expect(io.run.mock.calls[0]![0].collect).toBeUndefined();
    expect(content).toContain("not queried again");
    expect(io.save).toHaveBeenCalledWith(expect.objectContaining({ threadId: "thread", requestSeq: 1, source: "report-resume" }));
  });
  it("cannot read saved evidence after access is revoked", async () => {
    io.access.mockRejectedValue(new Error("denied"));
    await expect(resumeSavedReport(input)).rejects.toThrow("denied");
    expect(io.read).not.toHaveBeenCalled();
    expect(io.run).not.toHaveBeenCalled();
  });
  it("cannot start after cancellation or report missing evidence as recovered", async () => {
    await expect(resumeSavedReport({ ...input, signal: AbortSignal.abort() })).rejects.toThrow();
    expect(io.read).not.toHaveBeenCalled();
    io.read.mockResolvedValue(undefined);
    await expect(resumeSavedReport(input)).rejects.toThrow("not found");
    expect(io.save).not.toHaveBeenCalled();
  });
});
