<script setup lang="ts">
import { useI18n } from "vue-i18n";
import type {
  DataAdapterConnection,
  DataAdapterInfo,
} from "@ontomato/contracts/data-adapters";
import type { BusinessConfigAdapterDisplay } from "../../business-config";

// The connection fields one adapter type declares, with its examples as placeholders; edits the given connection in place.
const { t } = useI18n();

defineProps<{
  adapter: DataAdapterInfo;
  adapterDisplay: BusinessConfigAdapterDisplay;
}>();

const connection = defineModel<DataAdapterConnection>("connection", { required: true });
</script>

<template>
  <el-row v-if="adapter.fields.includes('url')" :gutter="24">
    <el-col :span="adapterDisplay.urlSpan">
      <el-form-item :label="t('admin.connectionUrl')">
        <el-input v-model="connection.url" :placeholder="adapter.examples.url" />
      </el-form-item>
    </el-col>
  </el-row>
  <el-row
    v-if="
      adapter.fields.includes('company') ||
      adapter.fields.includes('user') ||
      adapter.fields.includes('password') ||
      adapter.fields.includes('token')
    "
    :gutter="24"
  >
    <el-col v-if="adapter.fields.includes('company')" :span="adapterDisplay.credentialSpan">
      <el-form-item :label="t('admin.company')">
        <el-input v-model="connection.company" :placeholder="adapter.examples.company" />
      </el-form-item>
    </el-col>
    <el-col v-if="adapter.fields.includes('user')" :span="adapterDisplay.credentialSpan">
      <el-form-item :label="t('common.username')">
        <el-input v-model="connection.user" :placeholder="adapter.examples.user" />
      </el-form-item>
    </el-col>
    <el-col v-if="adapter.fields.includes('password')" :span="adapterDisplay.credentialSpan">
      <el-form-item :label="t('common.password')">
        <el-input
          v-model="connection.password"
          type="password"
          placeholder="******"
          show-password
        />
      </el-form-item>
    </el-col>
    <el-col v-if="adapter.fields.includes('token')" :span="adapterDisplay.credentialSpan">
      <el-form-item :label="t('admin.gatewayToken')">
        <el-input
          v-model="connection.token"
          type="password"
          :placeholder="adapter.examples.token || '******'"
          show-password
        />
      </el-form-item>
    </el-col>
  </el-row>
</template>
