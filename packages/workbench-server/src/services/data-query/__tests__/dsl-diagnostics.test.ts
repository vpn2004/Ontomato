import { describe, expect, it, vi } from "vitest";
import { diagnoseDsl, logDslDiagnostic } from "../dsl-diagnostics";
import { normalizeHarnessDataContent } from "../branches/abc-harness-payload";
const logs = vi.hoisted(() => ({ info: vi.fn(), warn: vi.fn() }));
vi.mock("../../../logging/logger", () => ({ createLogger: () => logs }));

const makeDsl = (properties: unknown = { field: "id", operator: "=", value: "sensitive" }) => ({
  answer: { steps: [{
    graph: { patterns: [{
      objects: [{ idx: 0, class: "customer", conditions: { properties } }, { idx: 1, class: "order" }],
      relationship: [{ from: 0, to: 1, type: ["orders"], max_hops: -1 }],
    }] },
    output: { fields: [{ variable: "o", field: "id", function: "count" }], group_by: { fields: [], having: [] } },
  }, { table: "step1", output: { fields: [{ field: "id", distinct: true }] } }] },
});

describe("DSL response diagnostics", () => {
  it("accepts documented multi-step relationships, aggregation, distinct and logical predicates without mutation", () => {
    const dsl = makeDsl({ and: [{ field: "id", operator: "in", value: [1, 2] }, { not: [{ field: "name", operator: "is", value: null }] }] });
    const before = JSON.stringify(dsl);
    expect(diagnoseDsl(dsl)).toEqual({ status: "checked", issues: [], truncated: false });
    expect(JSON.stringify(dsl)).toBe(before);
  });
  it("pinpoints the observed array predicate without dropping its filter or logging values", () => {
    const dsl = makeDsl([{ field: "id", operator: "=", value: "secret-filter-value" }]);
    const before = JSON.stringify(dsl);
    const report = logDslDiagnostic(dsl, "test");
    expect(report.status).toBe("issues");
    expect(report.issues[0].path).toBe("$.answer.steps[0].graph.patterns[0].objects[0].conditions.properties");
    expect(JSON.stringify(dsl)).toBe(before);
    expect(JSON.stringify(logs.warn.mock.calls)).not.toContain("secret-filter-value");
  });
  it.each([null, [], "not JSON", 42])("diagnoses a malformed top level: %s", (value) => {
    expect(diagnoseDsl(value).issues[0].path).toBe("$");
  });
  it("reports invalid patterns, relationship endpoints and aggregation shapes", () => {
    expect(diagnoseDsl({ graph: { patterns: {} } }).issues[0].path).toBe("$.graph.patterns");
    const dsl = makeDsl();
    dsl.answer.steps[0].graph!.patterns[0].relationship[0].to = 999;
    expect(diagnoseDsl(dsl).issues[0].path).toContain("relationship[0].to");
    expect(diagnoseDsl({ graph: { patterns: [] }, output: { group_by: [] } }).issues[0].path).toBe("$.output.group_by");
  });
  it("distinguishes missing or unknown dialects from a checked DSL", () => {
    expect(diagnoseDsl(undefined).status).toBe("unavailable");
    expect(diagnoseDsl({ query: "unknown dialect" }).status).toBe("unsupported");
  });
  it("extracts an explicit DSL from nested harness payloads without treating Python as DSL", () => {
    const dsl = makeDsl();
    const content = normalizeHarnessDataContent(JSON.stringify({ payload: { dsl, code: "print(42)", data: [{ count: 42 }] } }));
    expect(content.dsl).toEqual(dsl);
    expect(content.code).toBe("print(42)");
    expect(diagnoseDsl(normalizeHarnessDataContent({ code: "print(42)", data: [] }).dsl).status).toBe("unavailable");
  });
  it("bounds recursive diagnostics", () => {
    const cyclic: Record<string, unknown> = {};
    cyclic.and = [cyclic];
    expect(diagnoseDsl(makeDsl(cyclic)).truncated).toBe(true);
  });
});
