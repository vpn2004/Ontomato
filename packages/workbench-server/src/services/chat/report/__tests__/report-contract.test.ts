import { describe, expect, it } from "vitest";
import { buildReportContract, reviseReportSubmission, validateReportSubmission } from "../contract";
import { createReportEvidence, createReportEvidenceTools, reportEvidenceContext } from "../evidence";

const evidence = createReportEvidence({ queryRunId: "q1", completed: true, datasets: [
  { subQuestion: "All supplier batches", data: [{ id: "A", quantity: 12 }, { id: "A", quantity: 12 }, { id: "B", quantity: 7 }] },
  { subQuestion: "Recall", data: [{ status: "running" }] },
  { subQuestion: "Repairs", data: [] },
] as any });
const contract = buildReportContract("Investigate supplier S. Include ALL supplier batches; distinguish associated from defective products.", "Write a report");
const statistics = new Map();
const valid = () => ({
  contractVersion: contract.version, markdown: "Supplier scope is supported. Recall is running.",
  requirements: contract.requirements.map((r) => ({ id: r.id, status: "supported", explanation: r.text, evidenceIds: evidence.datasets.map((d) => d.id) })),
  evidenceUse: evidence.datasets.map((d) => ({ id: d.id, reason: d.title })),
  claims: [{ id: "recall-status", statement: "Recall is running", kind: "cell", datasetId: evidence.datasets[1]!.id, row: 0, field: "status", value: "running" }],
});

describe("report completion contract", () => {
  it("edits exact prose spans atomically while preserving unrelated findings and evidence", () => {
    const draft = valid();
    draft.markdown = "Scope: associated products.\nRecall is not executed.\nOther verified findings.";
    const revised = reviseReportSubmission(draft as any, { markdownEdits: [
      { oldText: "Recall is not executed.", newText: "Recall is running." },
      { oldText: "Scope: associated products.", newText: "Scope: potentially associated products." },
    ] });
    expect(revised.markdown).toBe("Scope: potentially associated products.\nRecall is running.\nOther verified findings.");
    expect(revised.claims).toEqual(draft.claims);
    expect(revised.evidenceUse).toEqual(draft.evidenceUse);
    expect(draft.markdown).toContain("not executed");
    expect(validateReportSubmission(revised, contract, evidence, statistics).issues).toEqual([]);
  });
  it.each([
    { name: "ambiguous", markdown: "Repeated. Repeated.", edits: [{ oldText: "Repeated.", newText: "Changed." }] },
    { name: "missing", markdown: "Original.", edits: [{ oldText: "Absent.", newText: "Changed." }] },
    { name: "overlapping", markdown: "abcdef", edits: [{ oldText: "abcd", newText: "X" }, { oldText: "cdef", newText: "Y" }] },
    { name: "depends on another replacement", markdown: "Original.", edits: [{ oldText: "Original.", newText: "New." }, { oldText: "New.", newText: "Later." }] },
    { name: "empty result", markdown: "Original.", edits: [{ oldText: "Original.", newText: "" }] },
  ])("rejects $name prose edits without changing the saved draft", ({ markdown, edits }) => {
    const draft = { ...valid(), markdown };
    expect(() => reviseReportSubmission(draft as any, { markdownEdits: edits })).toThrow();
    expect(draft.markdown).toBe(markdown);
  });
  it("rejects simultaneous full Markdown and span edits", () => {
    expect(() => reviseReportSubmission(valid() as any, { markdown: "Replacement.", markdownEdits: [
      { oldText: "Recall is running.", newText: "Recall remains running." },
    ] })).toThrow();
  });
  it("accepts newly collected evidence in a targeted revision but still rejects invented references", () => {
    const extra = createReportEvidence({ queryRunId: "extra", completed: true, datasets: [{ data: [{ id: "C" }] }] as any });
    const combined = { ...evidence, datasets: [...evidence.datasets, ...extra.datasets] };
    const revised = reviseReportSubmission(valid() as any, { evidenceUse: [{ id: extra.datasets[0]!.id, reason: "Additional scope" }] });
    expect(validateReportSubmission(revised, contract, combined, statistics).complete).toBe(true);
    const invented = reviseReportSubmission(revised, { evidenceUse: [{ id: "invented", reason: "Invalid" }] });
    expect(validateReportSubmission(invented, contract, combined, statistics).issues.join(" ")).toContain("Unknown evidence");
  });
  it("repairs only specified claims without losing saved prose, evidence, or requirements", () => {
    const draft = valid();
    draft.claims[0]!.value = "not executed";
    const revised = reviseReportSubmission(draft as any, { contractVersion: contract.version,
      claims: [{ ...draft.claims[0], value: "running" }] });
    expect(revised.markdown).toBe(draft.markdown);
    expect(revised.requirements).toEqual(draft.requirements);
    expect(validateReportSubmission(revised, contract, evidence, statistics).issues).toEqual([]);
    expect(draft.claims[0]!.value).toBe("not executed");
    expect(() => reviseReportSubmission(draft as any, { requirements: [{ ...draft.requirements[0], id: "unknown" }] })).toThrow("Unknown requirement");
  });
  it("retains original clauses even when the derived instruction is shorter", () => {
    expect(contract.requirements.map((r) => r.text).join(" ")).toContain("ALL supplier batches");
    expect(buildReportContract(contract.originalQuestion, "Write a report")).toEqual(contract);
  });
  it("accepts supported cell claims and preserves successful empty evidence", () => {
    expect(validateReportSubmission(valid(), contract, evidence, statistics).issues).toEqual([]);
  });
  it.each(["missing", "replace", "duplicate"])("rejects %s requirement IDs even when a model says it finished", (mode) => {
    const report = valid();
    if (mode === "missing") report.requirements.pop();
    else report.requirements[0]!.id = mode === "replace" ? "made-up" : report.requirements[1]!.id;
    expect(validateReportSubmission(report, contract, evidence, statistics).issues.join(" ")).toContain("requirement");
  });
  it("rejects an incorrect recall status and a missing evidence table", () => {
    const report = valid(); report.claims[0]!.value = "not executed"; report.evidenceUse.pop();
    const issues = validateReportSubmission(report, contract, evidence, statistics).issues.join(" ");
    expect(issues).toContain("recall-status"); expect(issues).toContain("evidence");
  });
  it("does not mark truncated evidence or blocked requirements complete", () => {
    const incomplete = structuredClone(evidence);
    incomplete.datasets[0]!.truncated = true;
    expect(validateReportSubmission(valid(), contract, incomplete, statistics).complete).toBe(false);
    const report = valid(); report.requirements[0]!.status = "blocked";
    expect(validateReportSubmission(report, contract, evidence, statistics).complete).toBe(false);
  });

  it("allows repaired scope without erasing a failed exploratory query", () => {
    const failed = createReportEvidence({ queryRunId: "failed", completed: false, error: "Query failed", datasets: [{ data: [] }] as any });
    const combined = { ...evidence, sourceComplete: false, datasets: [...evidence.datasets, ...failed.datasets] };
    const report = valid();
    report.evidenceUse.push({ id: failed.datasets[0]!.id, reason: "Failed exploratory attempt; complete replacement evidence cited in requirements" });
    expect(validateReportSubmission(report, contract, combined, statistics).complete).toBe(true);
    report.requirements[0]!.evidenceIds = [failed.datasets[0]!.id];
    expect(validateReportSubmission(report, contract, combined, statistics).complete).toBe(false);
  });
});

describe("stored evidence tools", () => {
  it("pages the stored planning ontology without conflating it with business query evidence", async () => {
    const bundle = { ...evidence, ontology: { capturedAt: "2026-11-13T00:00:00Z", markdown: "A".repeat(12000) + "replacement installs batch" } };
    const context = JSON.parse(reportEvidenceContext(bundle));
    expect(context.ontology).toMatchObject({ capturedAt: bundle.ontology.capturedAt, characters: bundle.ontology.markdown.length });
    expect(context.ontology).not.toHaveProperty("markdown");
    const tool = createReportEvidenceTools(() => bundle, new Map()).find((tool) => tool.name === "read_report_ontology")!;
    const first = JSON.parse((await tool.execute("schema", {})).content[0]!.text);
    expect(first.nextOffset).toBe(12000);
    const last = JSON.parse((await tool.execute("schema-next", { offset: first.nextOffset })).content[0]!.text);
    expect(first.text + last.text).toBe(bundle.ontology.markdown);
    expect(last.nextOffset).toBeNull();
    expect(last.notice).toContain("not business event evidence");
    await expect(tool.execute("invalid", { limit: -1 })).rejects.toThrow();
    const legacy = createReportEvidenceTools(() => evidence, new Map()).find((tool) => tool.name === "read_report_ontology")!;
    expect(JSON.parse((await legacy.execute("missing", {})).content[0]!.text).available).toBe(false);
  });
  it("bounds oversized ontology reads and lets the caller finish from the exact next offset", async () => {
    const markdown = "x".repeat(24446);
    const bundle = { ...evidence, ontology: { capturedAt: "2026-11-13T00:00:00Z", markdown } };
    const tool = createReportEvidenceTools(() => bundle, new Map()).find((tool) => tool.name === "read_report_ontology")!;
    const page = JSON.parse((await tool.execute("middle", { offset: 12000, limit: 12446 })).content[0]!.text);
    expect(page.text).toHaveLength(12000);
    expect(page.nextOffset).toBe(24000);
    const tail = JSON.parse((await tool.execute("tail", { offset: page.nextOffset })).content[0]!.text);
    expect(tail.text).toHaveLength(446);
    expect(tail.nextOffset).toBeNull();
  });
  it("returns raw values and projected display metadata once, with display rows only on request", async () => {
    const bundle = createReportEvidence({ queryRunId: "display", completed: true, datasets: [{ data: [
      { status: "running", quantity: 7 },
    ], fieldDisplayPlan: { status: { kind: "none", displayName: "Status" }, quantity: { kind: "none", displayName: "Quantity" } } }] as any });
    const before = structuredClone(bundle);
    const tool = createReportEvidenceTools(() => bundle, new Map())[0]!;
    const params = { datasetId: bundle.datasets[0]!.id, fields: ["status"] };
    const page = JSON.parse((await tool.execute("read", params)).content[0]!.text);
    expect(page.rows).toEqual([{ status: "running" }]);
    expect(page).not.toHaveProperty("displayRows");
    expect(page.fieldDisplayPlan).toEqual({ status: bundle.datasets[0]!.fieldDisplayPlan!.status });
    const displayed = JSON.parse((await tool.execute("display", { ...params, includeDisplay: true })).content[0]!.text);
    expect(displayed.displayRows).toEqual([{ status: "running" }]);
    expect(displayed.rows).toEqual(page.rows);
    expect(bundle).toEqual(before);
  });
  it("keeps every value and absolute row reference in a wide evidence page", async () => {
    const rows = Array.from({ length: 51 }, (_, index) => ({
      source_material_batch_number: index,
      downstream_product_business_key: index % 2 ? null : "A",
      nested_measurement_reading: [false, 0],
    }));
    const bundle = createReportEvidence({ queryRunId: "rows", completed: true, datasets: [{ data: rows }] as any });
    const tool = createReportEvidenceTools(() => bundle, new Map())[0]!;
    const response = await tool.execute("read", { datasetId: bundle.datasets[0]!.id, offset: 1 });
    const page = JSON.parse(response.content[0]!.text);
    expect(page.offset).toBe(1);
    expect(page.nextOffset).toBeNull();
    expect(page.rowValues.map((row: unknown[]) => Object.fromEntries(page.columns.map((key: string, i: number) => [key, row[i]])))).toEqual(rows.slice(1));
    expect(response.content[0]!.text.length).toBeLessThan(JSON.stringify(rows.slice(1)).length);
  });
  it("inspects provenance without returning data and leaves a valid cursor for later row reads", async () => {
    const bundle = createReportEvidence({ queryRunId: "provenance", completed: true, datasets: [{
      data: Array.from({ length: 60 }, (_, id) => ({ id })), abcProgram: { code: "all_pages()" },
    }] as any });
    const tool = createReportEvidenceTools(() => bundle, new Map())[0]!;
    const params = { datasetId: bundle.datasets[0]!.id, includeQuery: true, limit: 0 };
    const page = JSON.parse((await tool.execute("query", params)).content[0]!.text);
    expect(page.rows).toEqual([]);
    expect(page.query.program).toBe("all_pages()");
    expect(page.totalRows).toBe(60);
    expect(page.nextOffset).toBe(0);
    const normal = JSON.parse((await tool.execute("rows", { datasetId: params.datasetId })).content[0]!.text);
    expect(normal.rows).toHaveLength(50);
    expect(normal.nextOffset).toBe(50);
    const end = JSON.parse((await tool.execute("end", { ...params, offset: 60 })).content[0]!.text);
    expect(end.nextOffset).toBeNull();
    await expect(tool.execute("invalid", { ...params, limit: -1 })).rejects.toThrow("Invalid evidence page");
  });
  it("preserves evidence capture time and exposes unknown time for legacy snapshots", () => {
    const captured = createReportEvidence({ queryRunId: "time", completed: true, datasets: [{ data: [{ id: 1 }] }] as any });
    const time = (captured.datasets[0] as any).capturedAt;
    expect(Number.isFinite(Date.parse(time))).toBe(true);
    expect(JSON.parse(reportEvidenceContext(captured)).datasets[0].capturedAt).toBe(time);
    const legacy = structuredClone(captured);
    delete (legacy.datasets[0] as any).capturedAt;
    expect(JSON.parse(reportEvidenceContext(legacy)).datasets[0].capturedAt).toBeNull();
  });
  it("sums every saved integer quantity, preserving duplicate rows, nulls, filters and receipts", async () => {
    const bundle = createReportEvidence({ queryRunId: "sum", completed: true, datasets: [{ data: [
      { id: "A", quantity: 12 }, { id: "A", quantity: 12 }, { id: "B", quantity: null }, { id: "C", quantity: -7 },
    ] }] as any });
    const receipts = new Map();
    const tool = createReportEvidenceTools(() => bundle, receipts)[1]!;
    const params = { datasetId: bundle.datasets[0]!.id, operation: "sum", fields: ["quantity"] };
    const receipt = JSON.parse((await tool.execute("sum", params)).content[0]!.text);
    expect(receipt.value).toBe(17);
    expect(receipts.get(receipt.id)).toEqual(receipt);
    expect(JSON.parse((await tool.execute("filter", { ...params, filter: { id: "A" } })).content[0]!.text).value).toBe(24);
    expect(JSON.parse((await tool.execute("null", { ...params, filter: { id: "B" } })).content[0]!.text).value).toBeNull();
    expect(JSON.parse((await tool.execute("empty", { ...params, filter: { id: "absent" } })).content[0]!.text).value).toBeNull();
  });
  it.each([[{ q: "12" }], [{ q: 1.2 }], [{ q: 1 }, {}], [{ q: Number.MAX_SAFE_INTEGER }, { q: 1 }]])(
    "rejects an inexact or missing quantity instead of silently coercing its sum: %j", async (...rows) => {
      const bundle = createReportEvidence({ queryRunId: "invalid-sum", completed: true, datasets: [{ data: rows }] as any });
      const receipts = new Map();
      await expect(createReportEvidenceTools(() => bundle, receipts)[1]!.execute("sum", {
        datasetId: bundle.datasets[0]!.id, operation: "sum", fields: ["q"],
      })).rejects.toThrow();
      expect(receipts.size).toBe(0);
    });
  it("retains the executed query for scope checks without copying its code into every preview", async () => {
    const source = { code: "rows = query_all_pages(); return rows", outKey: "rows" };
    const bundle = createReportEvidence({ queryRunId: "program", completed: true, datasets: [{ data: [{ id: "A" }], abcProgram: source }] as any });
    source.code = "changed later";
    expect(reportEvidenceContext(bundle)).not.toContain("query_all_pages");
    const result = await createReportEvidenceTools(() => bundle, new Map())[0]!.execute("read", {
      datasetId: bundle.datasets[0]!.id, includeQuery: true,
    });
    expect(JSON.parse(result.content[0]!.text).query.program).toContain("query_all_pages");
  });
  it("preserves JSON object equality across JSONB key reordering without changing array or value semantics", async () => {
    const nested = createReportEvidence({ queryRunId: "nested", completed: true, datasets: [{ data: [
      { key: { b: 2, a: 1 } }, { key: { a: 1, b: 2 } }, { key: { a: "1", b: 2 } },
    ] }] as any });
    const receipts = new Map();
    const tool = createReportEvidenceTools(() => nested, receipts)[1]!;
    const result = await tool.execute("count", { datasetId: nested.datasets[0]!.id, operation: "distinct", fields: ["key"] });
    expect(JSON.parse(result.content[0]!.text).value).toBe(2);
    const filtered = await tool.execute("filter", { datasetId: nested.datasets[0]!.id, operation: "count", filter: { key: { a: 1, b: 2 } } });
    expect(JSON.parse(filtered.content[0]!.text).value).toBe(2);
    const report = valid() as any;
    report.evidenceUse = nested.datasets.map((d) => ({ id: d.id, reason: "Object-valued source evidence" }));
    report.requirements.forEach((r: any) => { r.evidenceIds = [nested.datasets[0]!.id]; });
    report.claims = [{ id: "object", kind: "cell", statement: "Source object", datasetId: nested.datasets[0]!.id, row: 0, field: "key", value: { a: 1, b: 2 } }];
    expect(validateReportSubmission(report, contract, nested, receipts).issues).toEqual([]);
  });
  it("counts the full distinct key set with a computation receipt", async () => {
    const tools = createReportEvidenceTools(() => evidence, statistics);
    const result = await tools[1]!.execute("call", { datasetId: evidence.datasets[0]!.id, operation: "distinct", fields: ["id"] });
    const receipt = JSON.parse(result.content[0]!.text);
    expect(receipt.value).toBe(2);
    const report = valid() as any;
    report.claims.push({ id: "count", statement: "Two distinct batches", kind: "statistic", statisticId: receipt.id, value: 2 });
    expect(validateReportSubmission(report, contract, evidence, statistics).issues).toEqual([]);
    report.claims[1].value = 3;
    expect(validateReportSubmission(report, contract, evidence, statistics).issues.join(" ")).toContain("count");
  });
  it("reads middle rows excluded from a small prompt without changing stored evidence", async () => {
    const large = createReportEvidence({ queryRunId: "q2", completed: true, datasets: [{ subQuestion: "Middle evidence", data: Array.from({ length: 200 }, (_, n) => ({ id: n, status: n === 100 ? "running" : "closed" })) }] as any });
    const before = large.version;
    expect(reportEvidenceContext(large, "en", 500)).not.toContain('"id":100');
    const result = await createReportEvidenceTools(() => large, new Map())[0]!.execute("read", { datasetId: large.datasets[0]!.id, offset: 100, limit: 1 });
    expect(JSON.parse(result.content[0]!.text).rows).toEqual([{ id: 100, status: "running" }]);
    expect(large.version).toBe(before);
  });
  it("rejects out-of-scope references and cancelled reads", async () => {
    const tool = createReportEvidenceTools(() => evidence, statistics)[0]!;
    await expect(tool.execute("read", { datasetId: "another-thread" })).rejects.toThrow("authorized");
    await expect(tool.execute("read", { datasetId: evidence.datasets[0]!.id }, AbortSignal.abort())).rejects.toThrow();
  });
});
