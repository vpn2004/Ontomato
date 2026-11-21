import { beforeAll, describe, expect, it, vi } from "vitest";
import type { ResultTruncation } from "@ontomato/contracts/query-execution";
import { configureI18n } from "../../../i18n";
import { appTextEn } from "../../../i18n/app-text-en";
import { en, enLocaleMeta } from "../../../i18n/locales/en";

/**
 * A dashboard widget refresh keeps the data engine's partial-result marker: the ABC program reports
 * it beside its rows, a DSL on the answer pack the rows come from. Complete results stay unmarked.
 * Doubles: the backend POST boundary, the data engine URL, and the field display plan model.
 */
const io = vi.hoisted(() => ({ body: "" as string }));

vi.mock("../../../config/application", () => ({
  config: { dataQuery: { baseUrl: "http://engine" } },
}));

vi.mock("../../../utils/backend-client", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  backendPost: async () => ({
    text: io.body,
    headers: new Headers({ "content-type": "application/json" }),
  }),
}));

vi.mock("../../data-query/adapter", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  createFieldDisplayPlan: async () => ({}),
}));

const MARKER: ResultTruncation = {
  truncated: true,
  rowLimit: 100000,
  truncationHint: "Intermediate rows reached the processing cap.",
};

let queryDashboardData: typeof import("../dsl-execution").queryDashboardData;

beforeAll(async () => {
  configureI18n({
    defaultLocale: "en",
    languageSwitchEnabled: false,
    appTextLocale: "en",
    packs: { en: { messages: { ...en, ...appTextEn }, ...enLocaleMeta } },
  });
  ({ queryDashboardData } = await import("../dsl-execution"));
});

const widget = { chartType: "bar", chartTitle: "Sites", fields: { x: "site" } };

describe("dashboard widget refresh", () => {
  it("ABC program: keeps the top-level marker, zero rows included", async () => {
    io.body = JSON.stringify({ success: true, data: [], ...MARKER });
    const result = await queryDashboardData(
      { ...widget, abcProgram: { code: "print(1)", outKeyRefs: [{ key: "k" }] } },
      "tk"
    );
    expect(result).toMatchObject({ rawData: [], ...MARKER });
  });

  it("DSL: keeps the marker of the answer pack the rows come from", async () => {
    io.body = JSON.stringify({
      success: true,
      data: { data: [{ answer: [{ site: "A" }], ...MARKER }] },
    });
    const result = await queryDashboardData({ ...widget, dsl: { answer: {} } }, "tk");
    expect(result).toMatchObject({ rawData: [{ site: "A" }], ...MARKER });
  });

  it("complete results carry no marker, and a malformed marker is rejected", async () => {
    io.body = JSON.stringify({ success: true, data: { data: [{ answer: [{ site: "A" }] }] } });
    expect(await queryDashboardData({ ...widget, dsl: { answer: {} } }, "tk")).not.toHaveProperty(
      "truncated"
    );
    io.body = JSON.stringify({ success: true, data: [], truncated: true });
    await expect(
      queryDashboardData(
        { ...widget, abcProgram: { code: "print(1)", outKeyRefs: [{ key: "k" }] } },
        "tk"
      )
    ).rejects.toThrow(/partial result/);
  });
});
