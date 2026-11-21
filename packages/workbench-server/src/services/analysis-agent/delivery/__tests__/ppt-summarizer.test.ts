import { describe, expect, it, vi } from "vitest";
import { summarizeReportForPpt } from "../ppt-summarizer";
import { configureI18n } from "../../../../i18n";
import { appTextEn } from "../../../../i18n/app-text-en";
import { en, enLocaleMeta } from "../../../../i18n/locales/en";
const {invoke} = vi.hoisted(() => ({invoke: vi.fn()}));
vi.mock("../../../../config/model-factory", () => ({createModel: async () => ({invoke})}));
configureI18n({defaultLocale: "en", languageSwitchEnabled: false, appTextLocale: "en", packs: {en: {messages: {...en, ...appTextEn}, ...enLocaleMeta}}});

describe("PPT hierarchical summary", () => {
  it("processes the beginning and end of a long conversation in bounded batches", async () => {
    const seen: string[] = [];
    invoke.mockImplementation(async (messages) => {
      const input = JSON.parse(messages[1].content);
      seen.push(messages[1].content);
      return {content: JSON.stringify({title: input.title, subtitle: "Summary", slides: input.sections.map((section: {id: string; text: string}) => ({title: section.id, takeaway: section.text.includes("LAST-TURN") ? "LAST-TURN" : "FIRST-TURN", bullets: [], sourceSectionIds: [section.id], visual: "insight"}))})};
    });
    const result = await summarizeReportForPpt({title: "Conversation", report: "", cover: {title: "Custom title", subtitle: "Custom subtitle"}, sections: [{id: "early", heading: "Early", text: "FIRST-TURN" + "a".repeat(60000), hasChart: false}, {id: "last", heading: "Last", text: "LAST-TURN", hasChart: false}]});
    expect(seen.some(value => value.includes("FIRST-TURN"))).toBe(true);
    expect(seen.some(value => value.includes("LAST-TURN"))).toBe(true);
    expect(seen.every(value => value.length < 49000)).toBe(true);
    expect(result.slides.some(slide => slide.takeaway === "LAST-TURN")).toBe(true);
    expect(result.title).toBe("Custom title");
    expect(result.subtitle).toBe("Custom subtitle");
  });

  it("does not invoke the model after cancellation", async () => {
    invoke.mockClear();
    const controller = new AbortController(); controller.abort();
    await expect(summarizeReportForPpt({title: "X", report: "", sections: []}, controller.signal)).rejects.toThrow();
    expect(invoke).not.toHaveBeenCalled();
  });
});
