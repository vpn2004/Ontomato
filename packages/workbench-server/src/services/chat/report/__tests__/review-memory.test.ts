import { describe, expect, it } from "vitest";
import { buildReportReviewChanges } from "../review-memory";
import type { ReportSubmission } from "../contract";
import type { EvidenceStatistic } from "../evidence";

const report = { contractVersion: "contract", markdown: "Prefix. Old finding. Suffix.",
  claims: [{ id: "claim", kind: "cell", statement: "Status", datasetId: "table", row: 0, field: "status", value: "running" }],
  requirements: [{ id: "required", status: "supported", explanation: "Original scope", evidenceIds: ["table"] }],
  evidenceUse: [{ id: "table", reason: "Source" }],
} as ReportSubmission;
const statistic: EvidenceStatistic = { id: "stat", datasetId: "table", operation: "count", fields: [], value: 1, sourceTruncated: false };
const previous = { report, evidenceIds: ["table", "removed"], statistics: [statistic] };

describe("final verification revision context", () => {
  it("detects added, removed and changed items, including exact JSON type changes", () => {
    const next = structuredClone(report);
    next.markdown = "Prefix. Correct finding. Suffix.";
    next.claims[0]!.value = true;
    next.claims.push({ ...next.claims[0]!, id: "new-claim" });
    next.requirements[0]!.explanation = "Updated scope";
    next.evidenceUse = [];
    expect(buildReportReviewChanges(previous, next, ["table", "new"], [{ ...statistic, value: 2 }])).toEqual({
      markdownChanged: true, markdownChange: { oldText: "Old", newText: "Correct" },
      changedClaimIds: ["claim", "new-claim"], changedRequirementIds: ["required"], changedEvidenceUseIds: ["table"],
      newEvidenceIds: ["new"], removedEvidenceIds: ["removed"], changedStatisticIds: ["stat"],
    });
    expect(report.markdown).toContain("Old");
    expect(statistic.value).toBe(1);
  });
  it("does not duplicate a long rewrite or pretend an omitted diff is unchanged", () => {
    const next = { ...report, markdown: "A".repeat(20_000) };
    const changes = buildReportReviewChanges(previous, next, previous.evidenceIds, previous.statistics);
    expect(changes.markdownChanged).toBe(true);
    expect(changes.markdownChange).toBeUndefined();
    expect(changes.markdownChangeOmitted).toBe(true);
  });
  it("compares structured values independent of JSONB key order but preserves types", () => {
    const original = { ...report, claims: [{ ...report.claims[0]!, value: { b: 2, a: 1 } }] };
    const reordered: ReportSubmission = { ...report, claims: [{ ...report.claims[0]!, value: { a: 1, b: 2 } }] };
    const changes = buildReportReviewChanges({ ...previous, report: original }, reordered, previous.evidenceIds, previous.statistics);
    expect(changes.changedClaimIds).toEqual([]);
    expect(changes.markdownChanged).toBe(false);
    reordered.claims[0]!.value = { a: "1", b: 2 };
    expect(buildReportReviewChanges({ ...previous, report: original }, reordered, previous.evidenceIds, []).changedClaimIds).toEqual(["claim"]);
  });
});
