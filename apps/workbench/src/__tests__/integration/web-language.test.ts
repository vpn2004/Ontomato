// @vitest-environment happy-dom
import { beforeEach, expect, it, vi } from "vitest";
import { installWorkbenchI18n } from "@ontomato/workbench-ui/i18n";
import { workbenchContent } from "../../../web/src/workbench-content";

beforeEach(() => {
  localStorage.clear();
  window.history.replaceState({}, "", "/");
  vi.resetModules();
});

it("defaults to Chinese, saves preferences, and lets supported URL locales override storage", async () => {
  const { language, initialLocale, saveLocale } = await import("../../../web/src/language");
  expect(language.initialLocale).toBe("zh-CN");
  expect(document.documentElement.lang).toBe("zh-CN");
  saveLocale("en");
  expect(localStorage.getItem("app-locale")).toBe("en");
  expect(initialLocale()).toBe("en");
  window.history.replaceState({}, "", "/?locale=zh-CN");
  expect(initialLocale()).toBe("zh-CN");
  window.history.replaceState({}, "", "/?locale=fr");
  expect(initialLocale()).toBe("en");
  localStorage.setItem("app-locale", "fr");
  expect(initialLocale()).toBe("zh-CN");
});

it("keeps captured display content reactive when the selected language changes", async () => {
  const { language } = await import("../../../web/src/language");
  const i18n = installWorkbenchI18n(language);
  const feedback = workbenchContent.text.feedback;
  expect(feedback.submit).toBe("提交");
  i18n.global.locale.value = "en";
  expect(feedback.submit).toBe("Submit");
  i18n.global.locale.value = "zh-CN";
  expect(feedback.submit).toBe("提交");
});
