<script setup lang="ts">
/**
 * The actions of one result dataset, named when the body does not name it: data/DSL download, the
 * dashboard entry, and the on-demand-expanded DSL view, post-calculation program, and field lineage
 * details. A partial dataset shows its one completeness notice here, and its data downloads as JSON
 * that carries the truncation marker beside the rows, so the saved file stays marked as partial.
 *
 * Download and detail dialogs belong to this component itself; both the standalone body card and
 * the shared execution view that use it get the full set of actions, and the caller only needs to
 * provide the dataset and forward the dashboard event. Details that have no output do not render
 * buttons, and the expanded state is the component's internal display state.
 */
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import {
  ChevronDown,
  ChevronRight,
  Copy,
  Download,
  FileJson,
  FileText,
  LayoutDashboard,
  List,
  TriangleAlert,
} from "lucide-vue-next";
import ResultDetailDialogs from "./ResultDetailDialogs.vue";
import { downloadCsvFromData, downloadJson } from "../../utils/downloadUtils";
import type { ResultDataset, ResultDetailRequest } from "./types";

const props = defineProps<{
  dataset: ResultDataset;
  /** False where the body places this dataset: the body already names it right above. */
  showTitle: boolean;
  showDashboardAction: boolean;
}>();

const emit = defineEmits<{ addToDashboard: [] }>();

const { t } = useI18n();

const expanded = ref(false);
const detailRequest = ref<ResultDetailRequest | null>(null);

const hasRows = computed(() => props.dataset.rows.length > 0);
const hasDetails = computed(
  () => !!props.dataset.dsl || !!props.dataset.code || (props.dataset.outKeyRefs?.length || 0) > 0
);

function sanitizeFilename(name: string) {
  const clean = String(name || "")
    .replace(/[\r\n]/g, " ")
    .replace(/[\\/:*?"<>|]/g, "")
    .trim()
    .slice(0, 60);
  return clean || "data";
}

function downloadFilename() {
  return sanitizeFilename(props.dataset.subQuestion || props.dataset.title);
}

function downloadData() {
  const { rows, truncated, rowLimit, truncationHint, sourceWindows } = props.dataset;
  if (sourceWindows?.length) {
    downloadJson({ answer: rows, sourceWindows, ...(truncated ? { truncated, rowLimit, truncationHint } : {}) }, downloadFilename());
    return;
  }
  if (truncated) {
    downloadJson(
      { answer: rows, truncated, rowLimit, truncationHint },
      `${downloadFilename()} (${t("chat.partialFileSuffix")})`
    );
    return;
  }
  downloadCsvFromData(rows, downloadFilename());
}

function downloadDatasetDsl() {
  downloadJson(props.dataset.dsl, downloadFilename());
}

function openDetail(kind: ResultDetailRequest["kind"]) {
  detailRequest.value = {
    kind,
    title: props.dataset.title,
    dsl: props.dataset.dsl,
    code: props.dataset.code,
    outKeyRefs: props.dataset.outKeyRefs,
  };
}
</script>

<template>
  <div class="result-dataset">
    <div v-if="showTitle" class="result-dataset-head">
      <span class="result-dataset-title">{{ dataset.title }}</span>
    </div>
    <div v-for="(window, index) in dataset.sourceWindows || []" :key="index" class="result-dataset-window" role="note">
      <List :size="14" aria-hidden="true" />
      <span>{{ t("chat.resultWindow", { total: window.matchingTotal, start: window.returnedRows ? window.offset + 1 : 0, end: window.returnedRows ? window.offset + window.returnedRows : 0 }) }}</span>
    </div>
    <div v-if="dataset.truncated" class="result-dataset-partial" role="note">
      <TriangleAlert :size="14" class="result-dataset-partial-icon" />
      <div>
        <div>{{ t("chat.partialResult", { rowLimit: dataset.rowLimit }) }}</div>
        <div class="result-dataset-partial-hint">{{ dataset.truncationHint }}</div>
      </div>
    </div>
    <div class="result-dataset-buttons">
      <button
        v-if="hasRows"
        type="button"
        class="ui-btn ui-btn-secondary result-dataset-btn"
        @click="downloadData"
      >
        <Download :size="14" />
        <span>{{ dataset.truncated ? t("chat.downloadPartialJson") : t("chat.downloadData") }}</span>
      </button>
      <button
        v-if="hasRows && dataset.dsl"
        type="button"
        class="ui-btn ui-btn-secondary result-dataset-btn"
        @click="downloadDatasetDsl"
      >
        <FileJson :size="14" />
        <span>{{ t("chat.downloadDsl") }}</span>
      </button>
      <button
        v-if="hasRows && showDashboardAction"
        type="button"
        class="ui-btn ui-btn-secondary result-dataset-btn"
        @click="emit('addToDashboard')"
      >
        <LayoutDashboard :size="14" />
        <span>{{ t("chat.addToDashboard") }}</span>
      </button>
      <button
        v-if="hasDetails"
        type="button"
        class="ui-btn ui-btn-secondary result-dataset-btn"
        @click="expanded = !expanded"
      >
        <component :is="expanded ? ChevronDown : ChevronRight" :size="14" />
        <span>{{ expanded ? t("common.collapse") : t("common.detail") }}</span>
      </button>
    </div>
    <div v-if="expanded && hasDetails" class="result-dataset-details">
      <button
        v-if="dataset.dsl"
        type="button"
        class="ui-btn ui-btn-secondary result-dataset-btn"
        @click="openDetail('dsl')"
      >
        <FileText :size="14" />
        <span>{{ t("common.viewDsl") }}</span>
      </button>
      <button
        v-if="dataset.code"
        type="button"
        class="ui-btn ui-btn-secondary result-dataset-btn"
        @click="openDetail('code')"
      >
        <Copy :size="14" />
        <span>{{ t("common.code") }}</span>
      </button>
      <button
        v-if="(dataset.outKeyRefs?.length || 0) > 0"
        type="button"
        class="ui-btn ui-btn-secondary result-dataset-btn"
        @click="openDetail('lineage')"
      >
        <List :size="14" />
        <span>{{ t("common.lineage") }}</span>
      </button>
    </div>

    <ResultDetailDialogs :request="detailRequest" @close="detailRequest = null" />
  </div>
</template>

<style scoped>
.result-dataset {
  margin-top: 10px;
  padding: 8px 10px;
  border-radius: var(--radius-lg);
  background: var(--el-fill-color-extra-light);
  border: 1px solid var(--el-border-color-lighter);
}
.result-dataset-head {
  margin-bottom: 8px;
  font-size: 12px;
}
.result-dataset-title {
  color: var(--el-text-color-primary);
  font-weight: 600;
  word-break: break-word;
}
.result-dataset-buttons,
.result-dataset-details {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.result-dataset-details {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed var(--el-border-color-lighter);
}
.result-dataset-btn {
  font-size: 12px;
}
.result-dataset-window {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-bottom: 8px;
  padding: 7px 9px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--radius-md);
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}
.result-dataset-window svg { flex-shrink: 0; margin-top: 2px; }
.result-dataset-partial {
  display: flex;
  gap: 6px;
  margin-bottom: 8px;
  padding: 6px 8px;
  border-radius: var(--radius-md);
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
  font-size: 12px;
  line-height: 1.5;
}
.result-dataset-partial-icon {
  flex-shrink: 0;
  margin-top: 2px;
}
.result-dataset-partial-hint {
  color: var(--el-text-color-secondary);
}
</style>
