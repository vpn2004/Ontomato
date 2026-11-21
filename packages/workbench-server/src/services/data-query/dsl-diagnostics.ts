import { createLogger } from "../../logging/logger";

const logger = createLogger("dsl-diagnostics");
type RecordValue = Record<string, unknown>;
const record = (v: unknown): v is RecordValue => !!v && typeof v === "object" && !Array.isArray(v);
export interface DslDiagnostic {
  status: "unavailable" | "unsupported" | "checked" | "issues";
  issues: Array<{ path: string; expected: string }>;
  truncated: boolean;
}

// Response-side diagnostics only. Execution authorization and validation stay in the engine.
// Never log values, repair a predicate, or infer DSL from executable Python code.
export function diagnoseDsl(input: unknown): DslDiagnostic {
  const result: DslDiagnostic = { status: "checked", issues: [], truncated: false };
  if (input === undefined) return { ...result, status: "unavailable" };
  let value = input;
  const issue = (path: string, expected: string) => {
    result.status = "issues";
    if (result.issues.length < 20) result.issues.push({ path, expected });
    else result.truncated = true;
  };
  if (typeof value === "string") {
    try { value = JSON.parse(value); } catch { issue("$", "JSON object"); return result; }
  }
  if (!record(value)) { issue("$", "object"); return result; }

  let remaining = 512;
  const properties = (v: unknown, path: string, depth = 0) => {
    if (depth > 32 || --remaining < 0) { result.truncated = true; return; }
    if (!record(v)) { issue(path, "predicate or logical object, not an array"); return; }
    const logical = ["and", "or", "not"].filter((key) => key in v);
    if (logical.length) {
      for (const key of logical) {
        const children = v[key];
        if (!Array.isArray(children)) issue(`${path}.${key}`, "array of predicates");
        else children.slice(0, 512).forEach((child, i) => properties(child, `${path}.${key}[${i}]`, depth + 1));
        if (Array.isArray(children) && children.length > 512) result.truncated = true;
      }
    } else {
      if (typeof v.field !== "string" || !v.field) issue(`${path}.field`, "nonempty field name");
      if (typeof v.operator !== "string" || !v.operator) issue(`${path}.operator`, "operator name");
    }
  };
  const array = (v: unknown, path: string, visit: (v: unknown, path: string) => void) => {
    if (!Array.isArray(v)) { issue(path, "array"); return; }
    if (v.length > 512) result.truncated = true;
    for (let i = 0; i < Math.min(v.length, 512); i++) {
      if (--remaining < 0) { result.truncated = true; break; }
      visit(v[i], `${path}[${i}]`);
    }
  };
  const fields = (v: unknown, path: string) => array(v, path, (field, p) => {
    if (!record(field)) { issue(p, "field object"); return; }
    if (field.function !== undefined && typeof field.function !== "string") issue(`${p}.function`, "function name");
  });
  const step = (v: unknown, path: string) => {
    if (!record(v)) { issue(path, "step object"); return; }
    if (v.graph !== undefined) {
      if (!record(v.graph)) issue(`${path}.graph`, "object");
      else array(v.graph.patterns, `${path}.graph.patterns`, (pattern, p) => {
        if (!record(pattern)) { issue(p, "pattern object"); return; }
        const indices = new Set<unknown>();
        array(pattern.objects, `${p}.objects`, (obj, op) => {
          if (!record(obj)) { issue(op, "object definition"); return; }
          if (obj.idx !== undefined) indices.add(obj.idx);
          if (obj.conditions !== undefined) {
            if (!record(obj.conditions)) issue(`${op}.conditions`, "object");
            else if (obj.conditions.properties !== undefined) properties(obj.conditions.properties, `${op}.conditions.properties`);
          }
        });
        if (pattern.relationship !== undefined) array(pattern.relationship, `${p}.relationship`, (rel, rp) => {
          if (!record(rel)) { issue(rp, "relationship object"); return; }
          for (const key of ["from", "to"]) {
            if (!indices.has(rel[key])) issue(`${rp}.${key}`, "reference to an object idx");
          }
          if (!Array.isArray(rel.type) || rel.type.some((t) => typeof t !== "string")) issue(`${rp}.type`, "array of relationship names");
        });
      });
    }
    if (v.output !== undefined) {
      if (!record(v.output)) issue(`${path}.output`, "object");
      else {
        if (v.output.fields !== undefined) fields(v.output.fields, `${path}.output.fields`);
        if (v.output.group_by !== undefined) {
          const group = v.output.group_by;
          if (!record(group)) issue(`${path}.output.group_by`, "object");
          else {
            if (group.fields !== undefined) fields(group.fields, `${path}.output.group_by.fields`);
            if (group.having !== undefined) fields(group.having, `${path}.output.group_by.having`);
          }
        }
      }
    }
  };
  if (value.answer !== undefined) {
    if (!record(value.answer)) issue("$.answer", "object");
    else array(value.answer.steps, "$.answer.steps", step);
  } else if (value.steps !== undefined) array(value.steps, "$.steps", step);
  else if (value.graph !== undefined) step(value, "$");
  else result.status = "unsupported";
  return result;
}

export function logDslDiagnostic(input: unknown, source: string): DslDiagnostic {
  const diagnostic = diagnoseDsl(input);
  const metadata = { source, ...diagnostic, scope: "response_structure_only" };
  if (diagnostic.status === "issues") logger.warn("DSL response structure issues", metadata);
  else logger.info("DSL response structure diagnostic", metadata);
  return diagnostic;
}
