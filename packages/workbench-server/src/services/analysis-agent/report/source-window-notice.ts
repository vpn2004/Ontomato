import type { ResultSourceWindows } from "@ontomato/contracts/result-window";
import { t } from "../../../i18n";

// 范围说明随保存/导出的报告保留，不依赖模型主动复述来源限制。
export function sourceWindowNotice(sources: ResultSourceWindows[]): string {
  const windows = sources.flatMap(source => source.sourceWindows || []);
  const unique = [...new Map(windows.map(window => [JSON.stringify(window), window])).values()];
  return unique.map(window => `> ${t("query.sourceWindow", {
    total: window.matchingTotal, start: window.returnedRows ? window.offset + 1 : 0,
    end: window.returnedRows ? window.offset + window.returnedRows : 0,
  })}\n\n`).join("");
}
