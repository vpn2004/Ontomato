import { describe, expect, it } from "vitest";
import { createReportEvidence } from "../evidence";
import { buildCollectionContext } from "../collection-context";

const evidence = createReportEvidence({ queryRunId: "saved-query", completed: true, datasets: [{
  subQuestion: "Reached objects", data: Array.from({ length: 137 }, (_, i) => ({ id: `object-${i}`, flag: i % 2 === 0, optional: null, unrelated: "Do not forward this column" })),
}] as any });
const selection = [{ datasetId: evidence.datasets[0]!.id, fields: ["id", "flag", "optional"] }];

describe("saved evidence context for supplemental collection", () => {
  it("forwards the entire selected set beyond preview and page limits with exact values and provenance", () => {
    const before = structuredClone(evidence);
    const context = JSON.parse(buildCollectionContext(evidence, selection));
    const saved = context.datasets[0];
    expect(saved.datasetId).toBe(evidence.datasets[0]!.id);
    expect(saved.queryRunId).toBe("saved-query");
    expect(saved.capturedAt).toBe(evidence.datasets[0]!.capturedAt);
    expect(saved.sourceComplete).toBe(true);
    expect(saved.rows).toHaveLength(137);
    expect(saved.rows[136]).toEqual({ id: "object-136", flag: true, optional: null });
    expect(saved.rows[0]).not.toHaveProperty("unrelated");
    expect(evidence).toEqual(before);
  });
  it("preserves duplicate members and incomplete-source markers", () => {
    const source = createReportEvidence({ queryRunId: "partial", completed: false, datasets: [
      { data: [{ id: "same" }, { id: "same" }], truncated: true }, { data: [] },
    ] as any });
    const saved = JSON.parse(buildCollectionContext(source, [{ datasetId: source.datasets[0]!.id, fields: ["id"] }])).datasets[0];
    expect(saved.rows).toEqual([{ id: "same" }, { id: "same" }]);
    expect(saved.sourceComplete).toBe(false);
    expect(saved.truncated).toBe(true);
  });
  it("rejects references outside the report and unknown fields", () => {
    expect(() => buildCollectionContext(evidence, [{ datasetId: "other-report", fields: ["id"] }])).toThrow(/authorized/);
    expect(() => buildCollectionContext(evidence, [{ datasetId: selection[0]!.datasetId, fields: ["invented"] }])).toThrow(/field/i);
  });
  it("rejects oversized context without silently truncating the scope", () => {
    const large = createReportEvidence({ queryRunId: "large", completed: true, datasets: [{ data: [{ id: "x".repeat(60_000) }] }] } as any);
    expect(() => buildCollectionContext(large, [{ datasetId: large.datasets[0]!.id, fields: ["id"] }])).toThrow(/too large/i);
  });
  it("adds no data when no selection is requested", () => {
    expect(buildCollectionContext(evidence, undefined)).toBe("");
  });
});
