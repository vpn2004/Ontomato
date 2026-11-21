import { z } from "zod";
import { evidenceHash, evidenceValueKey, type EvidenceStatistic, type ReportEvidence } from "./evidence";

export interface ReportContract {
  version: string;
  originalQuestion: string;
  instruction: string;
  requirements: Array<{ id: string; text: string }>;
}

/** Preserve source clauses verbatim; derived planning cannot delete the user's scope. */
export function buildReportContract(originalQuestion: string, instruction: string): ReportContract {
  const clauses = originalQuestion.match(/[^，,。；;\n]+[，,。；;]?/gu)?.map((s) => s.trim()).filter(Boolean) ?? [];
  const requirements = [...new Set(clauses)].map((text) => ({ id: `req_${evidenceHash(text)}`, text }));
  return { version: evidenceHash([originalQuestion, instruction]), originalQuestion, instruction, requirements };
}

const claimSchema = z.discriminatedUnion("kind", [
  z.object({ id: z.string().min(1), statement: z.string().min(1), kind: z.literal("cell"),
    datasetId: z.string(), row: z.number().int().nonnegative(), field: z.string(), value: z.unknown() }),
  z.object({ id: z.string().min(1), statement: z.string().min(1), kind: z.literal("statistic"),
    statisticId: z.string(), value: z.unknown() }),
]);
export const reportSubmissionSchema = z.object({
  contractVersion: z.string(), markdown: z.string().min(1).max(150_000),
  requirements: z.array(z.object({ id: z.string(), status: z.enum(["supported", "empty_verified", "blocked"]),
    explanation: z.string().min(1), evidenceIds: z.array(z.string()) })),
  evidenceUse: z.array(z.object({ id: z.string(), reason: z.string().min(1) })),
  claims: z.array(claimSchema),
});
export type ReportSubmission = z.infer<typeof reportSubmissionSchema>;

export const REPORT_SUBMISSION_PARAMETERS = {
  type: "object", properties: {
    contractVersion: { type: "string" }, markdown: { type: "string" },
    requirements: { type: "array", items: { type: "object", properties: {
      id: { type: "string" }, status: { type: "string", enum: ["supported", "empty_verified", "blocked"] },
      explanation: { type: "string" }, evidenceIds: { type: "array", items: { type: "string" } },
    }, required: ["id", "status", "explanation", "evidenceIds"] } },
    evidenceUse: { type: "array", items: { type: "object", properties: { id: { type: "string" }, reason: { type: "string" } }, required: ["id", "reason"] } },
    claims: { type: "array", items: { type: "object", properties: {
      id: { type: "string" }, statement: { type: "string" }, kind: { type: "string", enum: ["cell", "statistic"] },
      datasetId: { type: "string", description: "Required for cell claims" }, row: { type: "integer", minimum: 0 }, field: { type: "string" },
      statisticId: { type: "string", description: "Required for statistic claims; use a returned computation ID" }, value: {
        description: "Exact JSON source value. Keep numbers numeric, booleans boolean, null null, and arrays/objects structured. Never stringify a number or a computation receipt.",
        anyOf: [{ type: "number" }, { type: "string" }, { type: "boolean" }, { type: "null" }, { type: "array", items: {} }, { type: "object", additionalProperties: true }],
      },
    }, required: ["id", "statement", "kind", "value"] } },
  }, required: ["contractVersion", "markdown", "requirements", "evidenceUse", "claims"],
};

/** Patch saved work by stable IDs; every revision still passes the complete validation gate. */
export function reviseReportSubmission(draft: ReportSubmission, value: unknown): ReportSubmission {
  const { markdownEdits, ...patch } = reportSubmissionSchema.partial().extend({
    markdownEdits: z.array(z.object({ oldText: z.string().min(1), newText: z.string() })).min(1).max(100).optional(),
  }).parse(value);
  if (patch.markdown !== undefined && markdownEdits) throw new Error("Use either markdown or markdownEdits, not both.");
  let markdown = patch.markdown ?? draft.markdown;
  if (markdownEdits) {
    // Resolve every span against the same saved draft; replacements cannot create later matches.
    const spans = markdownEdits.map(({ oldText, newText }) => {
      const start = draft.markdown.indexOf(oldText);
      if (start < 0 || draft.markdown.indexOf(oldText, start + 1) >= 0) throw new Error("Each markdownEdits.oldText must match exactly once in the saved draft. Include surrounding context to disambiguate.");
      return { start, end: start + oldText.length, newText };
    }).sort((a, b) => a.start - b.start);
    if (spans.some((span, i) => i > 0 && span.start < spans[i - 1]!.end)) throw new Error("Markdown edits must not overlap.");
    for (const span of spans.reverse()) markdown = markdown.slice(0, span.start) + span.newText + markdown.slice(span.end);
  }
  const merge = <T extends { id: string }>(original: T[], updates: T[] | undefined, label: string, allowNew = false): T[] => {
    if (!updates) return original;
    if (new Set(updates.map((item) => item.id)).size !== updates.length) throw new Error(`Duplicate ${label} IDs in revision.`);
    const result = new Map(original.map((item) => [item.id, item]));
    for (const item of updates) {
      if (!allowNew && !result.has(item.id)) throw new Error(`Unknown ${label}: ${item.id}`);
      result.set(item.id, item);
    }
    return [...result.values()];
  };
  return reportSubmissionSchema.parse({ ...draft, ...patch, markdown,
    requirements: merge(draft.requirements, patch.requirements, "requirement"),
    evidenceUse: merge(draft.evidenceUse, patch.evidenceUse, "evidence", true),
    claims: merge(draft.claims, patch.claims, "claim", true),
  });
}

function valueHint(value: unknown): string {
  const text = evidenceValueKey(value);
  return `${text?.slice(0, 500)} (${value === null ? "null" : Array.isArray(value) ? "array" : typeof value})`;
}

export function exactIdIssues(actual: string[], expected: string[], label: string): string[] {
  const seen = new Set(actual);
  const issues: string[] = [];
  if (seen.size !== actual.length) issues.push(`Duplicate ${label} IDs.`);
  for (const id of expected) if (!seen.has(id)) issues.push(`Missing ${label}: ${id}`);
  for (const id of seen) if (!expected.includes(id)) issues.push(`Unknown ${label}: ${id}`);
  return issues;
}

export function validateReportSubmission(
  value: unknown, contract: ReportContract, evidence: ReportEvidence, statistics: Map<string, EvidenceStatistic>,
): { report?: ReportSubmission; issues: string[]; complete: boolean } {
  const parsed = reportSubmissionSchema.safeParse(value);
  if (!parsed.success) return { issues: parsed.error.issues.map((e) => `${e.path.join(".")}: ${e.message}`), complete: false };
  const report = parsed.data;
  const issues = [
    ...exactIdIssues(report.requirements.map((r) => r.id), contract.requirements.map((r) => r.id), "requirement"),
    ...exactIdIssues(report.evidenceUse.map((r) => r.id), evidence.datasets.map((d) => d.id), "evidence"),
  ];
  if (report.contractVersion !== contract.version) issues.push(`Task contract version mismatch. Use contractVersion=${contract.version}; the evidence bundle version is a different identifier.`);
  const byId = new Map(evidence.datasets.map((d) => [d.id, d]));
  for (const requirement of report.requirements) {
    if (requirement.evidenceIds.some((id) => !byId.has(id))) issues.push(`Unknown evidence for requirement ${requirement.id}.`);
    if (requirement.status !== "blocked" && !requirement.evidenceIds.length) issues.push(`No evidence for requirement ${requirement.id}.`);
    if (requirement.status === "empty_verified" && requirement.evidenceIds.some((id) => {
      const d = byId.get(id); return !d || !d.sourceComplete || d.truncated || d.data.length > 0;
    })) issues.push(`Empty-result claim is not supported for requirement ${requirement.id}.`);
  }
  if (new Set(report.claims.map((c) => c.id)).size !== report.claims.length) issues.push("Duplicate claim IDs.");
  if (!report.claims.length && evidence.datasets.some((d) => d.data.length)) issues.push("Supply evidence-linked claims for the report's key facts.");
  let claimedStatisticsComplete = true;
  for (const claim of report.claims) {
    if (claim.kind === "cell") {
      const row = byId.get(claim.datasetId)?.data[claim.row];
      if (!row || !Object.hasOwn(row, claim.field) || evidenceValueKey(row[claim.field]) !== evidenceValueKey(claim.value)) issues.push(`Claim ${claim.id} does not match its source cell. At the supplied dataset/row/field the value is ${valueHint(row?.[claim.field])}; received ${valueHint(claim.value)}. Recheck the reference if a different fact was intended.`);
    } else {
      const stat = statistics.get(claim.statisticId);
      const sources = stat ? [stat.datasetId, ...(stat.inputs ?? []).map((input) => input.datasetId)] : [];
      if (!stat || sources.some((id) => !byId.has(id)) || evidenceValueKey(stat.value) !== evidenceValueKey(claim.value)) issues.push(`Claim ${claim.id} does not match a computed statistic. The referenced receipt value is ${valueHint(stat?.value)}; received ${valueHint(claim.value)}.`);
      if (stat && (stat.sourceTruncated || stat.sourceComplete === false || sources.some((id) => {
        const source = byId.get(id); return !source?.sourceComplete || source.truncated;
      }))) claimedStatisticsComplete = false;
    }
  }
  // Unused failed explorations remain visible, but a later complete query may repair the scope.
  const complete = issues.length === 0 && claimedStatisticsComplete && report.requirements.every((r) => r.status !== "blocked" &&
    r.evidenceIds.length > 0 && r.evidenceIds.every((id) => { const d = byId.get(id); return d?.sourceComplete && !d.truncated; }));
  return { report, issues, complete };
}
