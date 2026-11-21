<script setup lang="ts">
import { computed, ref } from "vue";
import { ElAlert, ElButton, ElDialog } from "element-plus";
import type { AdapterOntologyPreview } from "@ontomato/contracts/data-adapters";
import { useManagerAdapter, useManagerClient, useOntologyText } from "../context";
import { useManagerFeedback } from "../feedback";

const { adapter, capabilities } = useManagerAdapter();
const { previewAdapterOntology, importAdapterOntology } = useManagerClient();
const { ot } = useOntologyText();
const { message } = useManagerFeedback();
const emit = defineEmits<{ imported: [] }>();
const preview = ref<AdapterOntologyPreview | null>(null);
const visible = ref(false);
const loading = ref(false);
const saving = ref(false);
const definition = computed(() => JSON.stringify(preview.value?.ontology, null, 2));

async function inspect() {
  loading.value = true;
  preview.value = null;
  try {
    preview.value = await previewAdapterOntology();
    visible.value = true;
  } catch (error) {
    message.error(error instanceof Error ? error.message : ot("requestFailed"));
  } finally {
    loading.value = false;
  }
}

async function importReviewed() {
  if (!preview.value) return;
  saving.value = true;
  try {
    await importAdapterOntology(preview.value);
    visible.value = false;
    message.success(ot("adapterOntologyImported"));
    emit("imported");
  } catch (error) {
    message.error(error instanceof Error ? error.message : ot("requestFailed"));
    // A changed connection/schema requires a fresh preview before another attempt.
    preview.value = null;
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <div v-if="capabilities.schemaRead" class="adapter-schema-bar">
    <span>
      <strong>{{ adapter?.label }}</strong>
      {{ capabilities.schemaWrite ? '' : ot('adapterSchemaReadOnly') }}
    </span>
    <el-button :loading="loading" @click="inspect">{{ ot('previewAdapterOntology') }}</el-button>
    <el-dialog v-model="visible" :title="ot('previewAdapterOntology')" width="min(800px, 92%)" :close-on-click-modal="false">
      <el-alert :title="ot('adapterOntologyImportNotice')" type="warning" :closable="false" />
      <pre class="adapter-schema-preview">{{ definition }}</pre>
      <template #footer>
        <el-button :disabled="saving" @click="visible = false">{{ ot('cancel') }}</el-button>
        <el-button type="primary" :loading="saving" :disabled="!preview" @click="importReviewed">
          {{ ot('importAdapterOntology') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.adapter-schema-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  padding: 10px 20px;
  border-bottom: 1px solid var(--el-border-color-light);
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.adapter-schema-bar strong { margin-right: 8px; }
.adapter-schema-preview {
  max-height: 50vh;
  overflow: auto;
  padding: 12px;
  background: var(--el-fill-color-lighter);
  font-size: 12px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
