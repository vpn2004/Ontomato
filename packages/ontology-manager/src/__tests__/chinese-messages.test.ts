import { expect, it } from "vitest";
import { englishMessages, chineseMessages } from "../i18n";

it("Chinese covers Manager and shared messages without losing interpolation fields", () => {
  const flatten = (value: Record<string, unknown>, prefix = ""): Record<string, string> =>
    Object.fromEntries(Object.entries(value).flatMap(([key, entry]) => {
      const path = prefix ? `${prefix}.${key}` : key;
      return typeof entry === "string" ? [[path, entry]] : Object.entries(flatten(entry as Record<string, unknown>, path));
    }));
  for (const section of ["ontology", "shared"] as const) {
    const en = flatten(englishMessages[section]);
    const zh = flatten(chineseMessages[section]);
    expect(Object.keys(zh).sort()).toEqual(Object.keys(en).sort());
    const fields = (text: string) => [...new Set([...text.matchAll(/\{(\w+)\}/g)].map((m) => m[1]))].sort();
    for (const key of Object.keys(en)) expect(fields(zh[key]), key).toEqual(fields(en[key]));
  }
});
