import type { ResultTruncation } from "./query-execution";

export interface AnalysisChartPlan {
  chartId: string;
  title: string;
  chartType: string;
  xField?: string;
  yField?: string;
  sourceSubQuestion: string;
  reason?: string;
  seriesField?: string;
  sortBy?: ChartSortBy;
  sortOrder?: "asc" | "desc";
  topN?: number;
  excludeCategoryValues?: ChartCategoryValue[];
  skillId?: string;
  config?: Record<string, unknown>;
}

/** A chart drawn from a partial result carries that result's truncation marker. */
export interface AnalysisChartResult extends AnalysisChartPlan, Partial<ResultTruncation> {
  scopeId: string;
  html: string;
  skillId: string;
  dataCount: number;
  /** Chart placeholder identifier in the report */
  marker?: string;
  /** Source tracing */
  provenance?: ProvenanceInfo;
  /** Evidence summary */
  evidenceSummary?: string;
}

export interface AnalysisChartDiagnostic {
  scopeId: string;
  reason:
    | "no_data_candidates"
    | "no_visualization_skill"
    | "no_viable_plan"
    | "invalid_plan"
    | "invalid_render_output"
    | "skill_execution_failed"
    | "no_renderable_chart";
  message: string;
  severity: "info" | "warning";
}

export type ChartSortBy = "xField" | "yField" | "none";

export type ChartCategoryValue = string | number;

/**
 * Source tracing metadata
 */
export interface ProvenanceInfo {
  /** Source sub-question ID list */
  sourceQuestionIds: string[];
  /** Source chart ID list */
  sourceChartIds?: string[];
  /** Source dataset references */
  sourceDatasetRefs?: string[];
  /** Generation timestamp */
  generatedAt?: number;
  /** Generator type */
  generator: "llm" | "skill" | "system";
}
