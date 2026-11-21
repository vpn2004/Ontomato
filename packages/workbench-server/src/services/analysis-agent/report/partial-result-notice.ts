import type { ResultTruncation } from "@ontomato/contracts/query-execution";
import { t } from "../../../i18n";

/** Keep the source limitation in saved/exported report text even if the model omits it. */
export function partialReportNotice(source: string, truncation?: ResultTruncation): string {
  if (!truncation) return "";
  return `${t("query.partialSource", { source, rowLimit: truncation.rowLimit, hint: truncation.truncationHint })
    .split(/\r?\n/)
    .map((line) => `> ${line}`)
    .join("\n")}\n\n`;
}
