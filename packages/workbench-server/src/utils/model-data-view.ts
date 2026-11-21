import type { ResultSourceWindows } from "@ontomato/contracts/result-window";
import type { ResultTruncation } from "@ontomato/contracts/query-execution";

const DEFAULT_MAX_CHARS = 6000;

/** A sampled view of rows; the truncation marker is present only when the rows are a partial result. */
export interface ModelDataView<T> extends Partial<ResultTruncation>, ResultSourceWindows {
  totalRows: number;
  includedRows: number;
  omittedRows: number;
  isComplete: boolean;
  headRows: T[];
  tailRows: T[];
}

function createView<T>(rows: readonly T[], headCount: number, tailCount: number): ModelDataView<T> {
  const totalRows = rows.length;
  const isComplete = headCount + tailCount >= totalRows;
  const headRows = isComplete ? [...rows] : rows.slice(0, headCount);
  const tailRows = !isComplete && tailCount > 0 ? rows.slice(-tailCount) : [];
  const includedRows = headRows.length + tailRows.length;

  return {
    totalRows,
    includedRows,
    omittedRows: totalRows - includedRows,
    isComplete,
    headRows,
    tailRows,
  };
}

/**
 * Builds one model-facing view for one tabular dataset.
 *
 * `isComplete` tells the model whether it sees the whole answer. Rows of a partial result never are,
 * so their view has `isComplete: false` and carries the data engine's truncation marker; the row
 * counts still describe only the given rows, and `rowLimit` is the cap that was reached, not a total.
 *
 * Complete datasets are returned as-is. Oversized datasets retain contiguous
 * rows from both ends, balancing the two sides by serialized character size.
 * Rows are never split. Partial views always preserve both boundaries. If that
 * minimum head-and-tail view exceeds the soft budget, it is kept whole.
 */
export function buildModelDataView<T>(
  rows: readonly T[],
  {
    maxChars = DEFAULT_MAX_CHARS,
    truncation,
  }: { maxChars?: number; truncation?: Partial<ResultTruncation> & ResultSourceWindows } = {}
): ModelDataView<T> {
  if (!Number.isSafeInteger(maxChars) || maxChars <= 0) {
    throw new RangeError("maxChars must be a positive safe integer");
  }
  const view = { ...sampleRows(rows, maxChars), ...(truncation?.sourceWindows?.length ? { sourceWindows: truncation.sourceWindows } : {}) };
  return truncation?.truncated
    ? {
        ...view,
        isComplete: false,
        truncated: true,
        rowLimit: truncation.rowLimit,
        truncationHint: truncation.truncationHint,
      }
    : view;
}

function sampleRows<T>(rows: readonly T[], maxChars: number): ModelDataView<T> {
  const complete = createView(rows, rows.length, 0);
  if (JSON.stringify(complete).length <= maxChars || rows.length <= 1) {
    return complete;
  }

  const rowSizes = rows.map((row) => JSON.stringify([row]).length - 2);
  let headCount = 1;
  let tailCount = 1;
  let headChars = rowSizes[0];
  let tailChars = rowSizes[rowSizes.length - 1];

  while (headCount + tailCount < rows.length) {
    const preferredSide = headChars <= tailChars ? "head" : "tail";
    const sides =
      preferredSide === "head" ? (["head", "tail"] as const) : (["tail", "head"] as const);
    let added = false;

    for (const side of sides) {
      const rowIndex = side === "head" ? headCount : rows.length - tailCount - 1;
      const nextHeadCount = headCount + (side === "head" ? 1 : 0);
      const nextTailCount = tailCount + (side === "tail" ? 1 : 0);
      const candidate = createView(rows, nextHeadCount, nextTailCount);

      if (JSON.stringify(candidate).length <= maxChars) {
        headCount = nextHeadCount;
        tailCount = nextTailCount;
        if (side === "head") {
          headChars += rowSizes[rowIndex];
        } else {
          tailChars += rowSizes[rowIndex];
        }
        added = true;
        break;
      }
    }

    if (!added) {
      break;
    }
  }

  return createView(rows, headCount, tailCount);
}
