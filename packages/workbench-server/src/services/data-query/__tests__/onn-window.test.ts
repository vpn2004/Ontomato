import { beforeAll, describe, expect, it } from "vitest";
import { configureI18n } from "../../../i18n";
import { appTextEn } from "../../../i18n/app-text-en";
import { en, enLocaleMeta } from "../../../i18n/locales/en";
import { parseQueryAnswerPayload } from "../protocol";
import { buildDataset } from "../branches/abc-harness-dataset";
import { normalizeHarnessDataContent } from "../branches/abc-harness-payload";

beforeAll(() => configureI18n({ defaultLocale: "en", languageSwitchEnabled: false, appTextLocale: "en", packs: { en: { messages: { ...en, ...appTextEn }, ...enLocaleMeta } } }));
const resultWindow = {
  contract: "onn_binding_window_v1", answerComplete: true, sourceExhausted: false,
  matchingTotal: 262144, totalExact: true, offset: 0, count: 1, returnedRows: 1,
  consistency: "version_checked", dataVersion: "boot:1",
};
describe("显式窗口结果范围", () => {
  it("保留窗口范围且不标成预算截断", () => {
    const payload = parseQueryAnswerPayload({ answer: [{ id: "a" }], resultWindow }, "window test");
    expect(payload.sourceWindows).toEqual([resultWindow]);
    expect(payload.truncated).toBeUndefined();
    const content = normalizeHarnessDataContent({ answer: [{ id: "a" }], resultWindow });
    const dataset = buildDataset(content, 0, [{ id: "a" }]);
    expect(dataset.sourceWindows).toEqual([resultWindow]);
  });
  it("历史构造和分析重放保留来源，派生输出行数可以变化", async () => {
    const source = { ...resultWindow, count: 5000, returnedRows: 5000 };
    const payload = parseQueryAnswerPayload({ answer: [{ total: 5000 }], sourceWindows: [source] });
    const dataset = buildDataset({ question: "window summary", sourceWindows: payload.sourceWindows }, 0, [{ total: 5000 }]);
    const { buildQueryRunInput, toQueryExecutionFact } = await import("../query-fact");
    const { expandQueryResultDatasetEntries } = await import("../../analysis-agent/runtime/query-result");
    const { buildModelDataView } = await import("../../../utils/model-data-view");
    const { sourceWindowNotice } = await import("../../analysis-agent/report/source-window-notice");
    const input = buildQueryRunInput({ identity: { threadId: "window-test", requestSeq: 1, sourceKind: "test", sourceRef: "source" }, question: "window summary", fact: { datasets: [dataset] } });
    const fact = toQueryExecutionFact({ ...input, status: "completed", id: "saved", createdAt: new Date(), updatedAt: new Date() });
    const entries = expandQueryResultDatasetEntries({ datasets: fact.datasets as typeof dataset[] });
    expect(entries[0].sourceWindows).toEqual([source]);
    expect(buildModelDataView(entries[0].data, { truncation: entries[0] }).sourceWindows).toEqual([source]);
    expect(sourceWindowNotice(entries)).toContain("262144");
    expect(sourceWindowNotice(entries)).toContain("1–5000");
    expect(entries[0].truncated).toBeUndefined();
  });
  it("空窗口仍保留其历史来源范围", async () => {
    const source = { ...resultWindow, offset: 300000, returnedRows: 0, sourceExhausted: true };
    const payload = parseQueryAnswerPayload({ answer: [], resultWindow: source });
    const dataset = buildDataset({ question: "empty window", sourceWindows: payload.sourceWindows }, 0, []);
    const { expandQueryResultDatasetEntries } = await import("../../analysis-agent/runtime/query-result");
    expect(expandQueryResultDatasetEntries({ datasets: [dataset] })[0].sourceWindows).toEqual([source]);
  });
  it("来源证据数量有界", () => {
    expect(() => parseQueryAnswerPayload({ answer: [], sourceWindows: Array(257).fill(resultWindow) })).toThrow(/window/i);
  });
  it("不接受伪造完整性或不一致范围", () => {
    for (const change of [{ answerComplete: false }, { totalExact: false }, { returnedRows: 6000 }, { count: 2, returnedRows: 2 }, { consistency: "unknown" }, { matchingTotal: -1 }]) {
      expect(() => parseQueryAnswerPayload({ answer: [{ id: "a" }], resultWindow: { ...resultWindow, ...change } })).toThrow(/window/i);
    }
  });
});
