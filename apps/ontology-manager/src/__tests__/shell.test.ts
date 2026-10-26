import { beforeEach, describe, expect, it, vi } from "vitest";
import { nextTick } from "vue";

/** Component double: records the props the shell hands to the component and can emit events on its behalf. */
const host = vi.hoisted(() => ({
  props: null as Record<string, unknown> | null,
  emit: null as ((event: string, ...args: unknown[]) => void) | null,
}));

// The whole component is doubled (it has its own tests); this file only checks the props the shell hands over, how component events are handled, and the status messages used.
vi.mock("@ontomato/ontology-manager", async () => {
  const { defineComponent, h } = await import("vue");
  const OntologyManager = defineComponent({
    props: {
      apiBase: { type: String, required: true },
      credential: { type: Object, required: true },
      locale: { type: String, required: true },
      messages: { type: Object, required: true },
      theme: { type: String, required: true },
      view: { type: String, required: true },
      dataFallbacks: { type: Object, required: true },
      presentation: { type: Object, required: true },
      knowledgeTagValues: { type: Object, required: true },
      embedded: { type: Boolean, required: true },
      knowledgeGovernanceUrl: { type: String, default: null },
    },
    emits: ["update:view", "auth-failure"],
    setup(props, { emit }) {
      host.props = props;
      host.emit = emit as typeof host.emit;
      return () => h("div", { class: "manager-double" });
    },
  });
  return { OntologyManager };
});

const { englishMessages, chineseMessages } = await import("@ontomato/ontology-manager/i18n");
const { isolateShellPerTest } = await import("./isolation");
const { ossProfile } = await import("../profile");
const { ossPresentation } = await import("../presentation");
const { startManagerShell } = await import("../shell");

const resetShell = isolateShellPerTest();

function start(url: string) {
  host.props = null;
  resetShell();
  document.body.innerHTML = '<div id="app"></div>';
  window.history.replaceState({}, "", url);
  startManagerShell(ossProfile);
}

describe("open-source standalone shell", () => {
  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    document.documentElement.lang = "en";
    window.__ONTOLOGY_MANAGER_CONFIG__ = { API_BASE: "/custom-base/api/", INITIAL_ROUTE: "" };
  });

  it("without parameters: explicit anonymous, Chinese by default and open-source data defaults, apiBase from config.js", () => {
    start("/?view=assets");
    expect(host.props).toMatchObject({
      // A relative or root-relative API_BASE resolves against the page URL, so one image serves any gateway prefix.
      apiBase: new URL("/custom-base/api", window.location.href).href,
      credential: { type: "anonymous" },
      locale: "zh-CN",
      messages: chineseMessages,
      theme: "light",
      view: "/assets/metrics",
      dataFallbacks: {
        noBusinessDescription: "No business description provided",
        unnamedAsset: "Unnamed asset",
      },
    });
    expect(host.props?.knowledgeTagValues).toEqual({
      general: "General",
      business: "Business",
      tech: "Tech",
    });
    expect(host.props?.embedded).toBe(false);
    expect(host.props?.knowledgeGovernanceUrl).toBeNull();
    // Open-source presentation values (b6437c86): the same static object is handed to the component.
    expect(host.props?.presentation).toBe(ossPresentation);
    expect(ossPresentation).toMatchObject({
      nodePalette: ["#639c2d", "#8bbf4a", "#d79b3b", "#58a968", "#d46b57", "#84956f"],
      edgeLabel: { labelPlacement: "center", labelAutoRotate: false },
      maxFitZoom: 1,
      compactDialogs: true,
    });
    expect(ossPresentation.dataBrowserDescription("Order")).toBe(
      "Browse and inspect data records for the current object type."
    );
  });

  it("host launch parameters: embedded=1 and an http(s) governance URL reach the component; URLs with other schemes count as absent", () => {
    start("/?embedded=1&knowledgeGovernanceUrl=http%3A%2F%2Fhost.test%2Fadmin%2Fknowledge-governance");
    expect(host.props?.embedded).toBe(true);
    expect(host.props?.knowledgeGovernanceUrl).toBe("http://host.test/admin/knowledge-governance");
    start("/?embedded=1&knowledgeGovernanceUrl=javascript%3Aalert(1)");
    expect(host.props?.knowledgeGovernanceUrl).toBeNull();
  });

  it("an explicit tk / apiKey launch uses that credential and removes it from the URL", () => {
    start("/?tk=t1#/data");
    expect(host.props?.credential).toEqual({ type: "token", token: "t1" });
    expect(window.location.search).toBe("");
    expect(host.props?.view).toBe("/data");
    start("/?apiKey=k1");
    expect(host.props?.credential).toEqual({ type: "apiKey", apiKey: "k1" });
  });

  it("honors URL then stored language, follows only parent locale messages, and ignores unsupported languages", async () => {
    localStorage.setItem("app-locale", "zh-CN");
    start("/?locale=en");
    expect(host.props?.locale).toBe("en");
    expect(host.props?.messages).toEqual(englishMessages);
    window.dispatchEvent(new MessageEvent("message", {
      data: { type: "ontomato-locale", locale: "zh-CN" }, source: window.parent,
    }));
    await nextTick();
    expect(host.props?.locale).toBe("zh-CN");
    expect(host.props?.messages).toEqual(chineseMessages);
    window.dispatchEvent(new StorageEvent("storage", { key: "app-locale", newValue: "ja" }));
    await nextTick();
    expect(host.props?.locale).toBe("zh-CN");
    expect(document.documentElement.lang).toBe("zh-CN");
    start("/");
    expect(host.props?.locale).toBe("zh-CN");
  });

  it("standalone language selection updates messages and persists without changing credentials", async () => {
    start("/");
    const select = document.querySelector("select")!;
    select.value = "en";
    select.dispatchEvent(new Event("change"));
    await nextTick();
    expect(host.props?.locale).toBe("en");
    expect(localStorage.getItem("app-locale")).toBe("en");
    expect(host.props?.credential).toEqual({ type: "anonymous" });
    start("/?embedded=1");
    expect(document.querySelector("select")).toBeNull();
  });

  it("theme messages use ontomato-theme", async () => {
    start("/");
    window.dispatchEvent(
      new MessageEvent("message", { data: { type: "ontomato-theme", theme: "dark" }, source: window })
    );
    await nextTick();
    expect(host.props?.theme).toBe("dark");
  });

  it("component navigation replaces the hash", async () => {
    start("/");
    const length = window.history.length;
    host.emit?.("update:view", "/objects/Order");
    await nextTick();
    expect(host.props?.view).toBe("/objects/Order");
    expect(window.location.hash).toBe("#/objects/Order");
    expect(window.history.length).toBe(length);
  });

  it("401: clears this tab's credential, stops rendering the component and reports the auth failure; a reload returns to the anonymous default", async () => {
    start("/?tk=t1");
    host.emit?.("auth-failure", 401);
    await nextTick();
    expect(sessionStorage.length).toBe(0);
    expect(document.querySelector(".manager-double")).toBeNull();
    expect(document.querySelector('[role="alert"]')?.textContent).toBe("请求失败（401）");
    start("/");
    expect(host.props?.credential).toEqual({ type: "anonymous" });
  });

  it("402: keeps the credential and the component without redirecting to login", async () => {
    start("/?tk=t1");
    host.emit?.("auth-failure", 402);
    await nextTick();
    expect(host.props?.credential).toEqual({ type: "token", token: "t1" });
    expect(sessionStorage.getItem("ontology_manager_token")).toBe("t1");
    expect(document.querySelector(".manager-double")).not.toBeNull();
  });
});
