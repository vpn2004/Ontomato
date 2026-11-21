import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { createApp, defineComponent, h, nextTick, type App } from "vue";
import { englishMessages, ontologyText } from "../i18n";
import ControlledActionPanel from "../components/ControlledActionPanel.vue";

const client = vi.hoisted(() => ({ loadControlledActions: vi.fn(), prepareControlledAction: vi.fn(), executeControlledAction: vi.fn(), controlledActionStatus: vi.fn() }));
vi.mock("../context", () => ({ useManagerClient: () => client, useOntologyText: () => ({ ot: (key: any, params: any) => ontologyText(englishMessages, key, params) }) }));
vi.mock("element-plus", () => {
  const model = (tag: string) => defineComponent({ props: ["modelValue", "disabled", "ariaLabel"], emits: ["update:modelValue"],
    setup: (props, { emit, slots }) => () => h(tag, { value: props.modelValue, disabled: props.disabled, "aria-label": props.ariaLabel,
      onInput: (event: Event) => emit("update:modelValue", (event.target as HTMLInputElement).value),
      onChange: (event: Event) => emit("update:modelValue", (event.target as HTMLInputElement).value) }, slots.default?.()) });
  return { ElInput: model("input"), ElSelect: model("select"), ElOption: defineComponent({ props: ["value", "label"], setup: p => () => h("option", { value: p.value }, p.label) }),
    ElButton: defineComponent({ props: ["disabled"], setup: (p, { slots }) => () => h("button", { disabled: p.disabled }, slots.default?.()) }),
    ElAlert: defineComponent({ props: ["title"], setup: p => () => h("div", { role: "alert" }, p.title) }) };
});
let app: App; let root: HTMLDivElement;
const prepared = { id: "decision-1", actionId: "carrier-level", actionName: "Contract level", objectId: "CR001", before: { contract_level: "normal" }, values: { contract_level: "core" }, expectedVersion: 7, state: "PREPARED", createdAt: 1 };
beforeEach(async () => {
  vi.clearAllMocks();
  client.loadControlledActions.mockResolvedValue({ enabled: true, subject: "ontomato-actions", actions: [{ id: "carrier-level", name: "Contract level", field: "contract_level", fieldLabel: "Contract level", values: ["normal", "core"] }], history: [] });
  client.prepareControlledAction.mockResolvedValue({ ...prepared }); client.executeControlledAction.mockResolvedValue({ ...prepared, state: "SUCCEEDED" });
  root = document.createElement("div"); document.body.append(root); app = createApp(ControlledActionPanel); app.mount(root); await flush();
});
afterEach(() => { app.unmount(); document.body.innerHTML = ""; });
async function flush() { for (let i = 0; i < 10; i++) { await Promise.resolve(); await nextTick(); } }
function click(text: string) { const button = [...root.querySelectorAll("button")].find(b => b.textContent === text); expect(button).toBeTruthy(); button!.click(); }
async function preview() {
  const input = root.querySelector("input")!; input.value = "CR001"; input.dispatchEvent(new Event("input"));
  const select = root.querySelectorAll("select")[1]; select.value = "core"; select.dispatchEvent(new Event("change")); await flush();
  click("Preview change"); await flush();
}
it("shows the shared identity and requires preview before confirmation", async () => {
  expect(root.textContent).toContain("ontomato-actions"); expect(root.textContent).not.toContain("Confirm change");
  await preview(); expect(client.prepareControlledAction).toHaveBeenCalledWith("carrier-level", "CR001", { contract_level: "core" });
  expect(client.executeControlledAction).not.toHaveBeenCalled(); expect(root.textContent).toContain("normal → core");
  click("Confirm change"); await flush(); expect(client.executeControlledAction).toHaveBeenCalledWith("decision-1"); expect(root.textContent).toContain("Succeeded");
});
it("changing the form invalidates the old preview", async () => {
  await preview(); const input = root.querySelector("input")!; input.value = "CR002"; input.dispatchEvent(new Event("input")); await flush();
  expect(root.textContent).not.toContain("Confirm change"); expect(client.executeControlledAction).not.toHaveBeenCalled();
});
it("lost execute responses recover and retry the same decision", async () => {
  await preview(); client.executeControlledAction.mockRejectedValueOnce(new Error("Lost response"));
  client.controlledActionStatus.mockResolvedValue({ ...prepared, state: "UNKNOWN" }); click("Confirm change"); await flush();
  expect(client.controlledActionStatus).toHaveBeenCalledWith("decision-1"); click("Check / retry this decision"); await flush();
  expect(client.executeControlledAction.mock.calls).toEqual([["decision-1"], ["decision-1"]]); expect(client.prepareControlledAction).toHaveBeenCalledTimes(1);
});
it("conflicts remain visible and cannot be resubmitted as confirmation", async () => {
  await preview(); client.executeControlledAction.mockResolvedValueOnce({ ...prepared, state: "CONFLICT" }); click("Confirm change"); await flush();
  expect(root.textContent).toContain("Version conflict"); expect(root.textContent).not.toContain("Confirm change");
});
