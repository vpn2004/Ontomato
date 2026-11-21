<script setup lang="ts">
import { ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { nodeApiGet } from "../../../api";
import { downloadJson } from "../../query-view/utils/downloadUtils";

const props = defineProps<{ threadId: string; requestSeq: number }>();
const { t } = useI18n();
const downloading = ref(false);
async function download() {
  if (downloading.value) return;
  downloading.value = true;
  try {
    const evidence = await nodeApiGet(`/threads/${encodeURIComponent(props.threadId)}/requests/${props.requestSeq}/download/report-evidence`);
    downloadJson(evidence, `report-evidence-${props.requestSeq}`);
  } catch { ElMessage.error(t("chat.reportEvidenceDownloadFailed")); }
  finally { downloading.value = false; }
}
</script>

<template>
  <button class="ui-btn" :disabled="downloading" @click="download">{{ t("chat.downloadReportEvidence") }}</button>
</template>
