import type { ReportSubmission } from "./contract";
import { evidenceValueKey, type EvidenceStatistic } from "./evidence";

/** Describe actual changes without copying the entire previous report into another review. */
export function buildReportReviewChanges(
  previous: { report: ReportSubmission; evidenceIds: string[]; statistics: EvidenceStatistic[] },
  report: ReportSubmission, evidenceIds: string[], statistics: EvidenceStatistic[],
) {
  const changed = <T extends { id: string }>(before: T[], after: T[]) => {
    const old = new Map(before.map((item) => [item.id, evidenceValueKey(item)]));
    const current = new Map(after.map((item) => [item.id, evidenceValueKey(item)]));
    return [...new Set([...old.keys(), ...current.keys()])].filter((id) => old.get(id) !== current.get(id));
  };
  const markdownChanged = previous.report.markdown !== report.markdown;
  let markdownChange: { oldText: string; newText: string } | undefined;
  if (markdownChanged) {
    const before = previous.report.markdown; const after = report.markdown;
    let start = 0; let end = 0;
    while (start < Math.min(before.length, after.length) && before[start] === after[start]) start++;
    while (end < Math.min(before.length, after.length) - start && before[before.length - end - 1] === after[after.length - end - 1]) end++;
    const oldText = before.slice(start, before.length - end); const newText = after.slice(start, after.length - end);
    if (oldText.length + newText.length <= 8_000) markdownChange = { oldText, newText };
  }
  return {
    markdownChanged, markdownChange, markdownChangeOmitted: markdownChanged && !markdownChange ? true : undefined,
    changedClaimIds: changed(previous.report.claims, report.claims),
    changedRequirementIds: changed(previous.report.requirements, report.requirements),
    changedEvidenceUseIds: changed(previous.report.evidenceUse, report.evidenceUse),
    newEvidenceIds: evidenceIds.filter((id) => !previous.evidenceIds.includes(id)),
    removedEvidenceIds: previous.evidenceIds.filter((id) => !evidenceIds.includes(id)),
    changedStatisticIds: changed(previous.statistics, statistics),
  };
}
