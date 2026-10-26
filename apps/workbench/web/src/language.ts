import en from "element-plus/es/locale/lang/en";
import zhCN from "element-plus/es/locale/lang/zh-cn";
import publicEn from "@ontomato/workbench-ui/locales/en";
import publicZhCN from "@ontomato/workbench-ui/locales/zh-CN";
import type { WorkbenchLanguage, SupportedLocale } from "@ontomato/workbench-ui/i18n";

const preferenceKey = "app-locale";
const supported = ["zh-CN", "en"] as const;

export function initialLocale(): SupportedLocale {
  const requested = new URLSearchParams(window.location.search).get("locale");
  if (supported.includes(requested as typeof supported[number])) return requested as SupportedLocale;
  try {
    const saved = localStorage.getItem(preferenceKey);
    if (supported.includes(saved as typeof supported[number])) return saved as SupportedLocale;
  } catch {
    // Storage can be unavailable in restricted browser contexts.
  }
  return "zh-CN";
}

export function saveLocale(locale: SupportedLocale): void {
  document.documentElement.lang = locale;
  document.documentElement.dir = "ltr";
  try {
    localStorage.setItem(preferenceKey, locale);
  } catch {
    // Language switching still works without persistence.
  }
}

export const language: WorkbenchLanguage = {
  supported,
  defaultLocale: "zh-CN",
  initialLocale: initialLocale(),
  switchEnabled: true,
  save: saveLocale,
  messages: { en: publicEn, "zh-CN": publicZhCN },
  elementLocale: (locale) => locale === "zh-CN" ? zhCN : en,
};
saveLocale(language.initialLocale);
