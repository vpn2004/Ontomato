import { afterEach, expect, it, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createRouter, createMemoryHistory } from "vue-router";
import ElementPlus from "element-plus";
import { workbenchI18n } from "../../../i18n";
import SkillManager from "../components/SkillManager.vue";
import MarkdownEditorDialog from "../components/MarkdownEditorDialog.vue";
import CodeMirrorEditor from "../../../components/CodeMirrorEditor.vue";

const i18n = workbenchI18n();
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

it.each([true, false])("preserves domain toggles and exposes the correct actions for public=%s", async (isPublic) => {
  const requests: string[] = [];
  const skill = { id: "example", type: "knowledge", category: "analysis", tags: [], version: "1", path: "", enabled: true, isPublic };
  vi.stubGlobal("fetch", async (input: string, init?: Parameters<typeof fetch>[1]) => {
    const pathname = new URL(input, "http://localhost").pathname;
    requests.push(`${init?.method ?? "GET"} ${pathname}`);
    if (pathname.endsWith("/toggle")) return Response.json({ success: true, enabled: false });
    if (pathname.endsWith("/script")) return Response.json({ success: true, scriptCode: null });
    if (pathname.endsWith("/content")) return Response.json({ success: true, skillContent: "Current shared instructions" });
    return Response.json({ success: true, skills: [skill] });
  });
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: "/", component: SkillManager }] });
  await router.push("/");
  const wrapper = mount(SkillManager, { attachTo: document.body, global: { plugins: [i18n, ElementPlus, router] } });
  try {
    await flushPromises();
    await wrapper.get(".el-switch").trigger("click");
    await flushPromises();
    expect(requests).toContain("PUT /api/skills/example/toggle");
    expect(wrapper.get(".skill-card").classes()).toContain("disabled");
    await wrapper.get(".more-btn").trigger("click");
    await flushPromises();
    const menu = [...document.querySelectorAll<HTMLElement>(".el-dropdown-menu__item")];
    const labels = menu.map((item) => item.textContent?.trim());
    expect(labels.includes(i18n.global.t("common.delete"))).toBe(!isPublic);
    const label = i18n.global.t(isPublic ? "common.view" : "common.edit");
    menu.find((item) => item.textContent?.trim() === label)!.click();
    await flushPromises();
    const save = [...document.querySelectorAll("button")].find((button) => button.textContent?.trim() === i18n.global.t("common.save"));
    expect(!!save).toBe(!isPublic);
    const textarea = document.querySelector(".el-dialog textarea") as HTMLTextAreaElement;
    expect(textarea.disabled).toBe(isPublic);
    document.querySelector<HTMLElement>(".edit-btn")!.click();
    await flushPromises();
    const viewer = wrapper.getComponent(MarkdownEditorDialog);
    expect(viewer.getComponent(CodeMirrorEditor).props("readonly")).toBe(isPublic);
    expect(viewer.getComponent(CodeMirrorEditor).props("modelValue")).toBe("Current shared instructions");
    expect(requests.some((request) => request === "PUT /api/skills/example")).toBe(false);
  } finally {
    wrapper.unmount();
  }
});
