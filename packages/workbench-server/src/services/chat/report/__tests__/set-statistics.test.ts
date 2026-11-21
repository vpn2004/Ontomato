import { describe, expect, it } from "vitest";
import { createReportEvidence, createReportEvidenceTools, type EvidenceStatistic } from "../evidence";
import { buildReportContract, validateReportSubmission } from "../contract";

const evidence = createReportEvidence({ queryRunId: "sets", completed: true, datasets: [
  { data: [{ product: "A" }, { product: "B" }, { product: "B" }, { product: 1 }, { product: null }] },
  { data: [{ sn: "B" }, { sn: "C" }, { sn: "1" }, { sn: null }] },
] } as any);
const left = { datasetId: evidence.datasets[0]!.id, fields: ["product"] };
const right = { datasetId: evidence.datasets[1]!.id, fields: ["sn"] };
const make = (bundle = evidence) => {
  const statistics = new Map<string, EvidenceStatistic>();
  const tool = createReportEvidenceTools(() => bundle, statistics).find((t) => t.name === "compare_report_sets")!;
  return { statistics, tool };
};
describe("cross-table evidence set comparison", () => {
  it.each([
    ["intersection", 2, [["B"], [null]]],
    ["union", 6, [["A"], ["B"], [1], [null], ["C"], ["1"]]],
    ["difference", 2, [["A"], [1]]],
  ])("computes %s with exact typed tuple semantics and no duplicate inflation", async (operation, count, members) => {
    const { statistics, tool } = make();
    const response = JSON.parse((await tool.execute("compare", { left, right, operation })).content[0]!.text);
    expect(response.statistic.value).toBe(count);
    expect(response.members).toEqual(members);
    expect(statistics.get(response.statistic.id)).toMatchObject({ value: count, inputs: [left, right] });
    expect(response.membersComplete).toBe(true);
  });
  it("marks a member preview separately from source truncation and computes all rows", async () => {
    const bundle = createReportEvidence({ queryRunId: "large", completed: true, datasets: [
      { data: Array.from({ length: 130 }, (_, product) => ({ product })) }, { data: [{ sn: 129 }], truncated: true },
    ] } as any);
    const { tool } = make(bundle);
    const result = JSON.parse((await tool.execute("compare", { operation: "difference",
      left: { ...left, datasetId: bundle.datasets[0]!.id }, right: { ...right, datasetId: bundle.datasets[1]!.id },
    })).content[0]!.text);
    expect(result.statistic.value).toBe(129);
    expect(result.statistic.sourceTruncated).toBe(true);
    expect(result.statistic.sourceComplete).toBe(false);
    expect(result.members).toHaveLength(100);
    expect(result.membersComplete).toBe(false);
    expect(result.nextOffset).toBe(100);
    const last = JSON.parse((await tool.execute("next", { operation: "difference", offset: 100,
      left: { ...left, datasetId: bundle.datasets[0]!.id }, right: { ...right, datasetId: bundle.datasets[1]!.id },
    })).content[0]!.text);
    expect(last.members).toHaveLength(29);
    expect(last.nextOffset).toBeNull();
    expect(last.statistic.id).toBe(result.statistic.id);
  });
  it.each([
    { left: { ...left, datasetId: "unauthorized" }, right },
    { left: { ...left, fields: ["unknown"] }, right },
    { left: { ...left, fields: [] }, right },
    { left, right: { ...right, fields: ["sn", "sn"] } },
    { left, right, operation: "arbitrary" },
    { left, right, offset: -1 },
  ])("rejects invalid or unauthorized set arguments: %j", async (params) => {
    const { tool, statistics } = make();
    await expect(tool.execute("bad", { operation: "intersection", ...params })).rejects.toThrow();
    expect(statistics.size).toBe(0);
  });
  it("rejects missing keys instead of equating them to null", async () => {
    const bundle = structuredClone(evidence); bundle.datasets[0]!.data.push({});
    await expect(make(bundle).tool.execute("missing", { left, right, operation: "intersection" })).rejects.toThrow("missing");
  });
  it("checks every contributing dataset when accepting a statistic claim", async () => {
    const { tool, statistics } = make();
    const { statistic } = JSON.parse((await tool.execute("sets", { left, right, operation: "intersection" })).content[0]!.text);
    const contract = buildReportContract("Compare the affected and failing product sets", "Report");
    const submission = { contractVersion: contract.version, markdown: "Intersection has two members.",
      requirements: contract.requirements.map((r) => ({ id: r.id, status: "supported", explanation: r.text, evidenceIds: [left.datasetId] })),
      evidenceUse: evidence.datasets.map((d) => ({ id: d.id, reason: "Set input" })),
      claims: [{ id: "overlap", kind: "statistic", statement: "Two members overlap", statisticId: statistic.id, value: 2 }],
    };
    expect(validateReportSubmission(submission, contract, evidence, statistics).complete).toBe(true);
    const missing = { ...evidence, datasets: evidence.datasets.slice(0, 1) };
    expect(validateReportSubmission({ ...submission, evidenceUse: submission.evidenceUse.slice(0, 1) }, contract, missing, statistics).issues.join(" ")).toContain("computed statistic");
    const truncated = structuredClone(evidence); truncated.datasets[1]!.truncated = true;
    expect(validateReportSubmission(submission, contract, truncated, statistics).complete).toBe(false);
  });
});
