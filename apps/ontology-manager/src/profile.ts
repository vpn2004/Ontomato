import { englishMessages, chineseMessages } from "@ontomato/ontology-manager/i18n";
import type { ManagerShellProfile } from "./shell";
import { ossPresentation } from "./presentation";

export const ossProfile: ManagerShellProfile = {
  noCredential: { type: "anonymous" },
  themeMessage: "ontomato-theme",
  locale: "zh-CN",
  localeSync: { messageType: "ontomato-locale" },
  messages: { en: englishMessages, "zh-CN": chineseMessages },
  dataFallbacks: {
    noBusinessDescription: "No business description provided",
    unnamedAsset: "Unnamed asset",
  },
  presentation: ossPresentation,
  knowledgeTagValues: { general: "General", business: "Business", tech: "Tech" },
};
