import { afterEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElUpload, ElMessageBox } from "element-plus";
import { ApiRequestError } from "../../../utils/api";
import { workbenchI18n } from "../../../i18n";
import SkillSelector from "../../admin/components/SkillSelector.vue";
import SkillFormDialog from "../components/SkillFormDialog.vue";
import { skillsApi } from "../api";

afterEach(() => {
  vi.restoreAllMocks();
});

describe("skill creation during agent configuration", () => {
  it("confirms overwrite, imports the ZIP and preserves existing selections", async () => {
    const skill = { id: "imported-skill", type: "knowledge" as const, category: "analysis" as const, tags: [], enabled: true, version: "1" };
    vi.spyOn(skillsApi, "listAnalysisSkills").mockResolvedValueOnce([]).mockResolvedValueOnce([skill]);
    const importer = vi.spyOn(skillsApi, "importSkill")
      .mockRejectedValueOnce(new ApiRequestError("Exists", 409, { code: "SKILL_EXISTS", details: { skillId: skill.id } }))
      .mockResolvedValueOnce({ success: true, skill });
    vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    const wrapper = mount(SkillSelector, {
      props: { modelValue: ["existing-skill"] },
      global: { plugins: [workbenchI18n(), ElementPlus], stubs: { SkillFormDialog: true } },
    });
    try {
      await flushPromises();
      const file = new File(["zip"], "skill.zip");
      await wrapper.getComponent(ElUpload).props("onChange")!({ raw: file } as any, []);
      await flushPromises();
      expect(importer).toHaveBeenCalledTimes(2);
      expect(importer.mock.calls[0][0].get("file")).toBeInstanceOf(File);
      expect(importer.mock.calls[1][1]).toBe(true);
      expect(wrapper.emitted("imported")?.[0]).toEqual([skill]);
      expect(wrapper.emitted("update:modelValue")?.at(-1)).toEqual([["existing-skill", skill.id]]);
    } finally {
      wrapper.unmount();
    }
  });

  it.each(["analysis", "visualization"] as const)(
    "refreshes %s skills and selects the newly created skill without losing existing selections",
    async (mode) => {
      const skill = {
        id: "new-skill", type: "executable" as const, category: mode,
        title: "New skill", tags: [], enabled: true, version: "1.0.0",
      };
      const list = vi.spyOn(skillsApi, mode === "analysis" ? "listAnalysisSkills" : "listVisualizationSkills")
        .mockResolvedValueOnce([]).mockResolvedValueOnce([skill]);
      const wrapper = mount(SkillSelector, {
        props: { mode, modelValue: ["existing-skill"] },
        global: {
          plugins: [workbenchI18n(), ElementPlus],
          stubs: { SkillFormDialog: true },
        },
      });
      try {
        await flushPromises();
        await wrapper.findAll(".skill-selector-toolbar button").at(-1)!.trigger("click");
        const form = wrapper.getComponent(SkillFormDialog);
        expect(form.props("visible")).toBe(true);
        expect(form.props("initialCategory")).toBe(mode);
        expect(form.props("lockCategory")).toBe(true);
        expect(wrapper.emitted("update:modelValue")).toBeUndefined();
        form.vm.$emit("created", skill);
        await flushPromises();
        expect(list).toHaveBeenCalledTimes(2);
        expect(wrapper.get(".skill-title").text()).toBe("New skill");
        expect(wrapper.emitted("update:modelValue")?.at(-1)).toEqual([["existing-skill", "new-skill"]]);
      } finally {
        wrapper.unmount();
      }
    }
  );
});
