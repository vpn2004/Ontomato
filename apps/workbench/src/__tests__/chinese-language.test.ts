import { expect, it } from "vitest";
import { getLanguageName, getOutputLanguageInstruction, resolveLocale, tForLocale, tApp } from "@ontomato/workbench-server/i18n/index";
import { en } from "@ontomato/workbench-server/i18n/locales/en";
import { zhCN } from "@ontomato/workbench-server/i18n/locales/zh-CN";
import { configureOssI18n } from "../i18n";

configureOssI18n();

it("registers Chinese and English output instructions while preserving fixed protocol text", () => {
  expect(resolveLocale(undefined)).toBe("zh-CN");
  expect(resolveLocale("ja")).toBe("zh-CN");
  expect(getLanguageName("zh-CN")).toBe("Simplified Chinese");
  expect(getOutputLanguageInstruction("zh-CN")).toContain("Simplified Chinese");
  expect(getOutputLanguageInstruction("en")).toContain("in English");
  expect(tForLocale("zh-CN", "query.progress.splitDone", { n: 3 })).toBe("分解完成，共 3 个子问题");
  expect(tForLocale("en", "query.progress.splitDone", { n: 3 })).toBe("Decomposition complete, 3 sub-questions total");
  expect(tApp("queryFixed.261", { v0: "data query response" })).toBe("data query response protocol error: answer must be an array");
  expect(Object.keys(zhCN).sort()).toEqual(Object.keys(en).sort());
});

it("preserves optional error details and their length cap", () => {
  expect(tForLocale("zh-CN", "api.executionFailed", { status: 500 })).toBe("执行失败：500");
  expect(tForLocale("zh-CN", "api.executionFailed", { status: 500, detail: "x".repeat(600) }))
    .toBe(`执行失败：500 | ${"x".repeat(500)}`);
});
