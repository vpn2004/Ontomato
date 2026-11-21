import { t } from "../../../i18n";


function escapeCsvCell(value: string): string {
  if (/[",\r\n]/.test(value)) {
    return `"${value.replace(/"/g, '""')}"`;
  }
  return value;
}

/** Saves a value as a `.json` file; a string is taken as JSON text already (a saved DSL). */
export function downloadJson(value: unknown, filename: string) {
  const normalized = typeof value === "string" ? value : JSON.stringify(value, null, 2);
  const blob = new Blob([normalized], { type: "application/json;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  try {
    const link = document.createElement("a");
    link.href = url;
    link.download = `${filename}.json`;
    document.body.appendChild(link);
    link.click();
    link.remove();
  } finally {
    URL.revokeObjectURL(url);
  }
}

export function downloadCsvFromData(data: any, filename: string) {
  let rows: Record<string, unknown>[];
  if (Array.isArray(data)) {
    rows = data;
  } else if (data?.rows && Array.isArray(data.rows)) {
    rows = data.rows;
  } else if (data?.data && Array.isArray(data.data)) {
    rows = data.data;
  } else {
    const blob = new Blob([t("analysis.noDataToExport")], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `${filename}.csv`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
    return;
  }

  const headers = Array.from(new Set(rows.flatMap((row) => Object.keys(row))));
  const csvRows = [headers, ...rows.map((row) => headers.map((h) => String(row[h] ?? "")))];
  const csv = csvRows
    .map((row) => row.map((cell) => escapeCsvCell(String(cell ?? ""))).join(","))
    .join("\r\n");
  const blob = new Blob([`\uFEFF${csv}`], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  try {
    const link = document.createElement("a");
    link.href = url;
    link.download = `${filename}.csv`;
    document.body.appendChild(link);
    link.click();
    link.remove();
  } finally {
    URL.revokeObjectURL(url);
  }
}
