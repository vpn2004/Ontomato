import { expect, it, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
const io = vi.hoisted(() => ({ get: vi.fn(), download: vi.fn(), error: vi.fn() }));
vi.mock("../../../api", () => ({ nodeApiGet: io.get }));
vi.mock("../../query-view/utils/downloadUtils", () => ({ downloadJson: io.download }));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (key: string) => key }) }));
vi.mock("element-plus", () => ({ ElMessage: { error: io.error } }));
import ReportEvidenceDownload from "../components/ReportEvidenceDownload.vue";
it("uses the authenticated API and preserves the complete evidence envelope", async () => {
  const value = { phase: "partial", evidence: { datasets: [{ data: [] }, { data: [{ id: "extra" }] }] } };
  io.get.mockResolvedValue(value);
  const wrapper = mount(ReportEvidenceDownload, { props: { threadId: "thread/a", requestSeq: 0 } });
  await wrapper.get("button").trigger("click"); await flushPromises();
  expect(io.get).toHaveBeenCalledWith("/threads/thread%2Fa/requests/0/download/report-evidence");
  expect(io.download).toHaveBeenCalledWith(value, "report-evidence-0");
  wrapper.unmount();
});
