<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ElAlert, ElButton, ElInput, ElOption, ElSelect } from "element-plus";
import type { ControlledActionCatalog, ControlledActionDecision } from "@ontomato/contracts/controlled-actions";
import { useManagerClient, useOntologyText } from "../context";

const client = useManagerClient();
const { ot } = useOntologyText();
const catalog = ref<ControlledActionCatalog | null>(null);
const actionId = ref("");
const objectId = ref("");
const value = ref("");
const decision = ref<ControlledActionDecision | null>(null);
const busy = ref(false);
const error = ref("");
const definition = computed(() => catalog.value?.actions.find(a => a.id === actionId.value));
const states = { PREPARED: "controlledPrepared", EXECUTING: "controlledExecuting", SUCCEEDED: "controlledSucceeded",
  CONFLICT: "controlledConflict", REJECTED: "controlledRejected", UNKNOWN: "controlledUnknown" } as const;
watch([actionId, objectId, value], () => { decision.value = null; error.value = ""; });
async function load() {
  try {
    catalog.value = await client.loadControlledActions();
    if (!actionId.value) actionId.value = catalog.value.actions[0]?.id ?? "";
  } catch (e) { error.value = e instanceof Error ? e.message : String(e); }
}
async function prepare() {
  if (!definition.value) return;
  busy.value = true; error.value = "";
  try { decision.value = await client.prepareControlledAction(actionId.value, objectId.value, { [definition.value.field]: value.value }); await load(); }
  catch (e) { error.value = e instanceof Error ? e.message : String(e); }
  finally { busy.value = false; }
}
async function execute() {
  if (!decision.value) return;
  busy.value = true; error.value = "";
  try { decision.value = await client.executeControlledAction(decision.value.id); await load(); }
  catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
    // A lost response can follow a committed write. Recover the same persisted decision, without preparing another.
    try { decision.value = await client.controlledActionStatus(decision.value.id); } catch { /* Keep the original decision available. */ }
  } finally { busy.value = false; }
}
onMounted(load);
</script>

<template>
  <section v-if="catalog?.enabled" class="controlled-actions" :aria-label="ot('controlledTitle')">
    <h2>{{ ot('controlledTitle') }}</h2>
    <p>{{ ot('controlledIdentity', { subject: catalog.subject ?? '' }) }}</p>
    <div class="controlled-form">
      <label>{{ ot('controlledAction') }}<el-select v-model="actionId" :disabled="busy" :aria-label="ot('controlledAction')">
        <el-option v-for="a in catalog.actions" :key="a.id" :label="a.name" :value="a.id" />
      </el-select></label>
      <label>{{ ot('controlledObject') }}<el-input v-model="objectId" :disabled="busy" :aria-label="ot('controlledObject')" /></label>
      <label>{{ definition?.fieldLabel }}<el-select v-model="value" :disabled="busy" :aria-label="definition?.fieldLabel">
        <el-option v-for="v in definition?.values ?? []" :key="v" :label="v" :value="v" />
      </el-select></label>
      <el-button :disabled="!definition || !objectId || !value || busy" :loading="busy" @click="prepare">{{ ot('controlledPreview') }}</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div v-if="decision" class="controlled-preview">
      <strong>{{ decision.actionName }} · {{ decision.objectId }}</strong>
      <p v-for="(after, field) in decision.values" :key="field">{{ definition?.fieldLabel }}: {{ decision.before[field] ?? '∅' }} → {{ after }}</p>
      <p>{{ ot('controlledVersion', { version: decision.expectedVersion }) }} · {{ ot(states[decision.state]) }}</p>
      <small>{{ ot('controlledDecisionId') }}: {{ decision.id }}</small>
      <el-button v-if="decision.state === 'PREPARED'" type="primary" :disabled="busy" :loading="busy" @click="execute">{{ ot('controlledConfirm') }}</el-button>
      <el-button v-if="decision.state === 'UNKNOWN' || decision.state === 'EXECUTING'" :disabled="busy" @click="execute">{{ ot('controlledRetry') }}</el-button>
    </div>
    <h3 v-if="catalog.history.length">{{ ot('controlledHistory') }}</h3>
    <ul class="controlled-history">
      <li v-for="item in catalog.history" :key="item.id">
        <button :disabled="busy" @click="decision = item">{{ item.actionName }} · {{ item.objectId }} · {{ ot(states[item.state]) }} · {{ new Date(item.createdAt).toLocaleString() }}</button>
      </li>
    </ul>
  </section>
  <el-alert v-else-if="error" :title="error" type="error" :closable="false" />
</template>

<style scoped>
.controlled-actions { margin-top: 20px; padding: 20px; border: 1px solid var(--el-border-color); border-radius: 8px; }
h2 { margin: 0; font-size: 18px; } h3 { margin-bottom: 8px; font-size: 14px; }
p, small { color: var(--el-text-color-secondary); }
.controlled-form { display: flex; flex-wrap: wrap; gap: 12px; align-items: end; margin: 16px 0; }
label { display: grid; gap: 8px; min-width: 160px; flex: 1; font-size: 13px; }
.controlled-preview { display: grid; justify-items: start; gap: 8px; margin-top: 16px; padding: 16px; background: var(--el-fill-color-light); border-radius: 6px; }
.controlled-preview p { margin: 0; }
.controlled-history { padding-left: 0; list-style: none; max-height: 220px; overflow: auto; }
.controlled-history button { border: 0; background: transparent; color: var(--el-color-primary); cursor: pointer; text-align: left; padding: 5px 0; }
</style>
