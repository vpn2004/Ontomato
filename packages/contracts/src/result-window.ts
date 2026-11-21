// 显式窗口满足用户请求，但未耗尽源结果时，必须保留范围与一致性事实。
export interface SourceResultWindow {
  contract: "onn_binding_window_v1";
  answerComplete: true;
  sourceExhausted: boolean;
  matchingTotal: number;
  totalExact: true;
  offset: number;
  count: number;
  returnedRows: number;
  consistency: "version_checked";
  dataVersion: string;
}
export interface ResultSourceWindows {
  sourceWindows?: SourceResultWindow[];
}

export function parseSourceResultWindow(value: unknown): SourceResultWindow {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Invalid result window");
  const v = value as Record<string, unknown>;
  if (v.contract !== "onn_binding_window_v1" || v.answerComplete !== true || v.totalExact !== true
    || typeof v.sourceExhausted !== "boolean" || v.consistency !== "version_checked"
    || typeof v.dataVersion !== "string" || !v.dataVersion || v.dataVersion.length > 256
    || [v.matchingTotal, v.offset, v.count, v.returnedRows].some(n => typeof n !== "number" || !Number.isSafeInteger(n) || n < 0)
    || (v.returnedRows as number) !== Math.min(v.count as number, Math.max(0, (v.matchingTotal as number) - (v.offset as number)))) {
    throw new Error("Invalid result window completeness or range");
  }
  return { contract: v.contract, answerComplete: true, sourceExhausted: v.sourceExhausted,
    matchingTotal: v.matchingTotal as number, totalExact: true, offset: v.offset as number,
    count: v.count as number, returnedRows: v.returnedRows as number,
    consistency: "version_checked", dataVersion: v.dataVersion };
}
