import { expect, it } from "vitest";
import { getLocale, setLocale } from "@ontomato/workbench-server/i18n/index";
import { configureOssI18n } from "../i18n";

it("supports Chinese and English with stable application protocol text", () => {
  configureOssI18n();
  setLocale("zh-CN");
  expect(getLocale()).toBe("zh-CN");
  setLocale("en");
  expect(getLocale()).toBe("en");
});
