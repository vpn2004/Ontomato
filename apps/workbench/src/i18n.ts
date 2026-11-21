import { appTextEn } from "@ontomato/workbench-server/i18n/app-text-en";
import { en, enLocaleMeta } from "@ontomato/workbench-server/i18n/locales/en";
import { zhCN, zhCNLocaleMeta } from "@ontomato/workbench-server/i18n/locales/zh-CN";
import { configureI18n } from "@ontomato/workbench-server";

export function configureOssI18n(): void {
  configureI18n({
    defaultLocale: "zh-CN",
    languageSwitchEnabled: true,
    appTextLocale: "en",
    packs: {
      "zh-CN": { messages: { ...zhCN }, ...zhCNLocaleMeta },
      en: {
        messages: { ...en, ...appTextEn },
        ...enLocaleMeta,
      },
    },
  });
}
