import { beforeEach, expect, it, vi } from "vitest";
const io = vi.hoisted(() => ({ access: vi.fn(), read: vi.fn() }));
vi.mock("../../../../infrastructure/connection", () => ({ getCheckpointer: () => ({ verifyThreadAccess: io.access }) }));
vi.mock("../store", () => ({ readReportCheckpoint: io.read }));
import { getReportEvidenceDownload } from "../download";
beforeEach(() => { vi.resetAllMocks(); });
it("downloads saved evidence with table boundaries, empty results, query provenance and verification status", async () => {
  const saved = { phase: "partial", contract: { originalQuestion: "All related objects" },
    evidence: { datasets: [{ id: "original", data: [{ quantity: 12 }] }, { id: "extra", data: [], program: "query()" }] },
    statistics: [{ id: "count", value: 1 }], review: { passed: false, issues: ["Scope incomplete"] } };
  io.read.mockResolvedValue(saved);
  const result = await getReportEvidenceDownload("thread", 0, "user", "domain");
  expect(io.access).toHaveBeenCalledWith("thread", "user", "domain");
  expect(JSON.parse(result.body)).toMatchObject(saved);
  expect(result.fileName).toMatch(/\.json$/);
  expect(result.contentType).toContain("application/json");
});
it("rejects revoked access before reading evidence", async () => {
  io.access.mockRejectedValue(new Error("Denied"));
  await expect(getReportEvidenceDownload("thread", 0, "other", "domain")).rejects.toThrow("Denied");
  expect(io.read).not.toHaveBeenCalled();
});
it("does not invent a successful download for invalid or missing evidence", async () => {
  await expect(getReportEvidenceDownload("thread", NaN, "user", "domain")).rejects.toThrow();
  expect(io.read).not.toHaveBeenCalled();
  await expect(getReportEvidenceDownload("thread", 0, "user", "domain")).rejects.toThrow("not found");
});
