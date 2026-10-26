import type { BusinessConfigProps } from "@ontomato/workbench-ui";

/** Original values of this edition's system config page (from BusinessConfig and its cards before extraction), passed through the /admin/system-config route props. */
export const businessConfig: BusinessConfigProps = {
  defaultLanguage: "zh-CN",
  adapterDisplay: {
    labelWidth: "150px",
    urlSpan: 24,
    credentialSpan: 12,
  },
  languageOptions: [{ value: "zh-CN", label: "简体中文" }, { value: "en", label: "English" }],
};
