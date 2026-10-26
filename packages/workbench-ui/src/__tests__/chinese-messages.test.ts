import { expect, it } from "vitest";
import { createI18n } from "vue-i18n";
import english from "../locales/en";
import chinese from "../locales/zh-CN";

function flatten(value: Record<string, unknown>, prefix = ""): Record<string, string> {
  return Object.fromEntries(Object.entries(value).flatMap(([key, entry]) => {
    const path = prefix ? `${prefix}.${key}` : key;
    return typeof entry === "string" ? [[path, entry]] : Object.entries(flatten(entry as Record<string, unknown>, path));
  }));
}

it("Chinese covers every UI key, preserves interpolation fields and compiles every message", () => {
  const en = flatten(english);
  const zh = flatten(chinese);
  expect(Object.keys(zh).sort()).toEqual(Object.keys(en).sort());
  const i18n = createI18n({ legacy: false, locale: "zh-CN", messages: { "zh-CN": chinese } });
  const fields = (text: string) => [...new Set([...text.matchAll(/\{(\w+)\}/g)].map((m) => m[1]))].sort();
  for (const key of Object.keys(en)) {
    expect(fields(zh[key]), key).toEqual(fields(en[key]));
    expect(i18n.global.t(key, Object.fromEntries(fields(zh[key]).map((name) => [name, 2]))), key).toBeTruthy();
  }
});
