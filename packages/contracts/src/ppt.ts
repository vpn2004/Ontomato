import type { ResultTruncation } from "./query-execution";

export interface PptSummarySlide {
  title: string;
  takeaway: string;
  bullets: string[];
  sourceSectionIds: string[];
  visual: "chart" | "insight" | "risk" | "action" | "metric";
}

export interface PptSummaryOutline {
  title: string;
  subtitle: string;
  slides: PptSummarySlide[];
}

export interface PptGenerationOptions {
  userInstructions?: string;
  cover?: { title: string; subtitle: string };
  coverImage?: string;
  coverImageMode?: "background" | "side";
}

export interface PptSourceSection extends Partial<ResultTruncation> {
  id: string;
  heading?: string;
  content: string;
  tables?: Array<{ columns: string[]; rows: unknown[][] }>;
}
