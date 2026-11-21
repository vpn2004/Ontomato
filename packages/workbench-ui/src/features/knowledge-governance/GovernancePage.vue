<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { ArrowLeft, BookOpen, ChevronRight, FileText, History, Plus, X } from "lucide-vue-next";
import AgentChatInput from "../../components/agent-chat/AgentChatInput.vue";
import AgentMessage from "../../components/agent-chat/AgentMessage.vue";
import { useGovernance } from "./useGovernance";
import { governanceReturnPath } from "./return-path";

const { t } = useI18n();
const router = useRouter(),
  route = useRoute();
const {
  session,
  messages,
  running,
  rounds,
  loading,
  sending,
  stopping,
  error,
  streamLost,
  source,
  select,
  reload,
  send,
  stop,
  readSource,
} = useGovernance();
const input = ref(""),
  panel = ref<"report" | "sources" | null>(null);
const conversation = ref<HTMLElement | null>(null);
const panelElement = ref<HTMLElement | null>(null);
const busy = computed(() => running.value || sending.value);
// Review counts appear only once the Agent has recorded an actual review scope.
const progress = computed(() => {
  const work = session.value?.work;
  return work && work.knowledgeTotal !== null
    ? t("governance.progress", { done: work.reviewedKnowledgeIds.length, total: work.knowledgeTotal })
    : "";
});
const issueCount = computed(() => session.value?.work.issues.length ?? 0);
// Clarifying replies are offered only when the Agent has finished a turn and waits for the user.
const awaitingReply = computed(
  () =>
    !busy.value &&
    session.value?.status === "idle" &&
    messages.value[messages.value.length - 1]?.role === "assistant"
);
watch(
  () => route.params.sessionId,
  (id) => {
    const next = typeof id === "string" && id ? id : undefined;
    // The route catching up to the round just created keeps the draft and panel.
    if (next !== session.value?.id) {
      input.value = "";
      panel.value = null;
    }
    void select(next);
  },
  { immediate: true }
);
watch(messages, async () => {
  const element = conversation.value;
  const nearEnd = !element || element.scrollHeight - element.scrollTop - element.clientHeight < 160;
  await nextTick();
  if (nearEnd && conversation.value) conversation.value.scrollTop = conversation.value.scrollHeight;
});
// Opening the panel moves focus into it (on narrow screens it hides the conversation);
// closing returns focus to whatever opened it.
let opener: HTMLElement | null = null;
watch(panel, async (value, previous) => {
  if (value && !previous) {
    opener = document.activeElement as HTMLElement | null;
    await nextTick();
    panelElement.value?.querySelector<HTMLElement>('[aria-pressed="true"]')?.focus();
  } else if (!value && previous) {
    await nextTick();
    if (opener?.isConnected) opener.focus();
    opener = null;
  }
});
// Switching sessions keeps the from query so Back still returns to the entry page.
function sessionLocation(sessionId?: string) {
  return {
    name: "KnowledgeGovernance",
    params: sessionId ? { sessionId } : {},
    query: { from: route.query.from },
  };
}
// Typed text and suggestions are the same user message; the first one creates the round.
async function submit(text: string, fromInput: boolean) {
  const id = await send(text);
  if (!id) return;
  // Clear only what was sent; text typed while the request was pending stays as the draft.
  if (fromInput && input.value.startsWith(text)) input.value = input.value.slice(text.length);
  if (route.params.sessionId !== id) await router.push(sessionLocation(id));
}
function showSource(id: string) {
  panel.value = "sources";
  void readSource(id);
}
function closeHistory(event: MouseEvent) {
  (event.currentTarget as HTMLElement).closest("details")!.open = false;
}
</script>

<template>
  <section class="kg-page">
    <header class="kg-header">
      <button
        class="kg-ghost kg-ghost--icon"
        :aria-label="t('governance.back')"
        @click="router.push(governanceReturnPath(route.query))"
      >
        <ArrowLeft :size="18" />
      </button>
      <h1>{{ t("governance.title") }}</h1>
      <div class="kg-toolbar">
        <button
          v-if="session"
          class="kg-ghost"
          :disabled="busy"
          @click="router.push(sessionLocation())"
        >
          <Plus :size="16" />
          <span class="kg-ghost__label">{{ t("governance.start") }}</span>
        </button>
        <details v-if="rounds.length" class="kg-history">
          <summary class="kg-ghost">
            <History :size="16" />
            <span class="kg-ghost__label">{{ t("governance.rounds") }}</span>
          </summary>
          <nav @click="closeHistory">
            <RouterLink
              v-for="round in rounds"
              :key="round.id"
              :to="sessionLocation(round.id)"
              :aria-current="round.id === session?.id ? 'page' : undefined"
            >
              <span>{{ new Date(round.createdAt).toLocaleString() }}</span>
              <small>{{ t(`governance.${round.status}`) }}</small>
            </RouterLink>
          </nav>
        </details>
        <button
          v-if="session"
          class="kg-ghost"
          :aria-pressed="panel !== null"
          @click="panel = panel ? null : 'report'"
        >
          <FileText :size="16" />
          <span class="kg-ghost__label">{{ t("governance.report") }}</span>
          <span v-if="issueCount" class="kg-count">{{ issueCount }}</span>
        </button>
      </div>
    </header>
    <p v-if="error" role="alert" class="kg-banner">{{ error }}</p>
    <p v-if="loading" role="status" class="kg-loading">{{ t("governance.loading") }}</p>
    <div v-else :class="['kg-body', { 'kg-body--panel': session && panel }]">
      <main class="kg-chat">
        <div
          ref="conversation"
          class="kg-scroll"
          role="log"
          :aria-label="t('governance.discussion')"
        >
          <div class="kg-thread">
            <div v-if="!session && !messages.length" class="kg-welcome">
              <h2>{{ t("governance.welcomeTitle") }}</h2>
              <p>{{ t("governance.welcomeBody") }}</p>
              <div class="kg-suggestions">
                <button
                  v-for="key in ['startCheck', 'findConflicts']"
                  :key="key"
                  type="button"
                  :disabled="busy"
                  @click="submit(t(`governance.${key}`), false)"
                >
                  {{ t(`governance.${key}`) }}
                </button>
              </div>
            </div>
            <AgentMessage v-for="(message, index) in messages" :key="index" :message="message" />
            <!-- Proposed changes live in the report; the thread points to them where they appear. -->
            <button
              v-if="issueCount || progress"
              type="button"
              class="kg-result"
              @click="panel = 'report'"
            >
              <FileText :size="18" class="kg-result__icon" />
              <span class="kg-result__text">
                <strong>
                  {{ issueCount ? t("governance.findings", issueCount) : t("governance.report") }}
                </strong>
                <small v-if="progress">{{ progress }}</small>
              </span>
              <ChevronRight :size="16" />
            </button>
            <p v-if="running" class="kg-working" role="status">
              <span class="kg-dots" aria-hidden="true"><i /><i /><i /></span>
              {{ stopping ? t("governance.stopping") : t("governance.working") }}
            </p>
            <p v-if="streamLost" class="kg-note kg-note--alert" role="status">
              {{ t("governance.disconnected") }}
              <button type="button" class="kg-link" @click="reload">{{ t("common.retry") }}</button>
            </p>
            <template v-else-if="session && !busy">
              <p v-if="session.error" role="alert" class="kg-note kg-note--alert">
                {{ t("governance.failedHint") }}
                <button type="button" class="kg-link" @click="submit(t('governance.continue'), false)">
                  {{ t("governance.continue") }}
                </button>
              </p>
              <p v-else-if="session.status === 'stopped'" class="kg-note">
                {{ t("governance.stoppedHint") }}
                <button type="button" class="kg-link" @click="submit(t('governance.continue'), false)">
                  {{ t("governance.continue") }}
                </button>
              </p>
            </template>
          </div>
        </div>
        <div class="kg-composer">
          <div v-if="awaitingReply" class="kg-replies">
            <button
              v-for="key in ['explain', 'defer']"
              :key="key"
              type="button"
              @click="submit(t(`governance.${key}`), false)"
            >
              {{ t(`governance.${key}`) }}
            </button>
          </div>
          <AgentChatInput
            v-model="input"
            :is-sending="busy"
            :placeholder="t('governance.placeholder')"
            :maxlength="20000"
            @send="submit(input, true)"
            @stop="stop"
          />
          <small>{{ t("governance.saved") }}</small>
        </div>
      </main>
      <aside
        v-if="session && panel"
        ref="panelElement"
        class="kg-panel"
        :aria-label="t(`governance.${panel}`)"
      >
        <header>
          <button
            v-for="tab in ['report', 'sources'] as const"
            :key="tab"
            type="button"
            class="kg-tab"
            :aria-pressed="panel === tab"
            @click="panel = tab"
          >
            {{ t(`governance.${tab}`) }}
          </button>
          <button
            type="button"
            class="kg-ghost kg-ghost--icon kg-close"
            :aria-label="t('governance.close')"
            @click="panel = null"
          >
            <X :size="16" />
          </button>
        </header>
        <div class="kg-panel__body">
          <template v-if="panel === 'report'">
            <p class="kg-hint">{{ t("governance.reportHint") }}</p>
            <p v-if="progress" class="kg-hint">{{ progress }}</p>
            <p v-if="session.work.summary" class="kg-summary">{{ session.work.summary }}</p>
            <p v-if="!issueCount" class="kg-hint">{{ t("governance.noIssues") }}</p>
            <article v-for="issue in session.work.issues" :key="issue.id" class="kg-issue">
              <div class="kg-tags">
                <span>{{ t(`governance.${issue.category}`) }}</span>
                <span :class="`kg-tag--${issue.status}`">{{ t(`governance.${issue.status}`) }}</span>
              </div>
              <h3>{{ issue.title }}</h3>
              <dl>
                <dt>{{ t("governance.problem") }}</dt>
                <dd>{{ issue.problem }}</dd>
                <template v-if="issue.before">
                  <dt>{{ t("governance.before") }}</dt>
                  <dd>{{ issue.before }}</dd>
                </template>
                <dt>{{ t("governance.after") }}</dt>
                <dd class="kg-after">{{ issue.after }}</dd>
                <dt>{{ t("governance.basis") }}</dt>
                <dd>{{ issue.basis }}</dd>
              </dl>
              <button
                v-for="id in issue.sourceIds"
                :key="id"
                type="button"
                class="kg-source"
                @click="showSource(id)"
              >
                <BookOpen :size="14" />
                {{
                  session.work.sources.find((item) => item.id === id)?.title ||
                  t("governance.sources")
                }}
              </button>
            </article>
          </template>
          <template v-else>
            <p v-if="!session.work.sources.length" class="kg-hint">
              {{ t("governance.noSources") }}
            </p>
            <button
              v-for="item in session.work.sources"
              :key="item.id"
              type="button"
              class="kg-source"
              :aria-current="source?.source.id === item.id ? 'true' : undefined"
              @click="readSource(item.id)"
            >
              <BookOpen :size="14" />
              {{ item.title }}
            </button>
            <section v-if="source" class="kg-original">
              <h3>{{ source.source.title }}</h3>
              <p v-if="!source.page" class="kg-hint">{{ t("governance.evidenceHint") }}</p>
              <p v-else-if="!source.page.items.length" class="kg-hint">
                {{ t("governance.sourceMissing") }}
              </p>
              <template v-else>
                <blockquote v-for="item in source.page.items" :key="item.id">
                  {{ item.text }}
                </blockquote>
                <button
                  v-if="source.page.next"
                  type="button"
                  class="kg-link"
                  @click="readSource(source.source.id, source.page.next.offset)"
                >
                  {{ t("governance.more") }}
                </button>
              </template>
            </section>
          </template>
        </div>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.kg-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-width: 0;
  min-height: 0;
  color: var(--el-text-color-primary);
}
button,
summary {
  font: inherit;
  color: inherit;
  cursor: pointer;
}
button:disabled {
  opacity: 0.5;
  cursor: default;
}
button:focus-visible,
summary:focus-visible,
a:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}

/* Header: one quiet row; the conversation is the page. */
.kg-header {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 52px;
  padding: 6px 16px;
  box-sizing: border-box;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
h1 {
  flex: 1;
  min-width: 0;
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kg-toolbar {
  display: flex;
  align-items: center;
  gap: 2px;
}
.kg-ghost {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 10px;
  border: 0;
  border-radius: 8px;
  background: none;
  font-size: 13px;
  color: var(--el-text-color-regular);
  white-space: nowrap;
}
.kg-ghost--icon {
  width: 32px;
  padding: 0;
  justify-content: center;
}
.kg-ghost:hover:not(:disabled),
.kg-ghost[aria-pressed="true"],
.kg-history[open] > summary {
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
}
.kg-count {
  min-width: 18px;
  padding: 0 5px;
  box-sizing: border-box;
  border-radius: 9px;
  background: var(--el-color-primary);
  color: var(--on-primary);
  font-size: 11px;
  line-height: 18px;
  text-align: center;
}
.kg-history {
  position: relative;
}
.kg-history summary {
  list-style: none;
}
.kg-history summary::-webkit-details-marker {
  display: none;
}
.kg-history nav {
  position: absolute;
  top: calc(100% + 6px);
  inset-inline-end: 0;
  z-index: 3;
  display: flex;
  flex-direction: column;
  width: min(300px, calc(100vw - 32px));
  max-height: min(320px, 50vh);
  overflow: auto;
  padding: 4px;
  box-sizing: border-box;
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;
  background: var(--el-bg-color-overlay, var(--el-bg-color));
  box-shadow: 0 8px 24px var(--shadow-md);
}
.kg-history a {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 10px;
  border-radius: 6px;
  font-size: 13px;
  color: var(--el-text-color-primary);
  text-decoration: none;
}
.kg-history a:hover,
.kg-history a[aria-current="page"] {
  background: var(--el-fill-color-light);
}
.kg-history small {
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}
.kg-banner {
  margin: 0;
  padding: 8px 16px;
  font-size: 13px;
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
  overflow-wrap: anywhere;
}
.kg-loading {
  padding: 24px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

/* Conversation column: one reading width shared by thread and composer. */
.kg-body {
  position: relative;
  flex: 1;
  display: flex;
  min-height: 0;
}
.kg-chat {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.kg-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
.kg-thread,
.kg-composer {
  width: 100%;
  max-width: 768px;
  margin: 0 auto;
  padding: 0 24px;
  box-sizing: border-box;
}
.kg-thread {
  display: flex;
  flex-direction: column;
  gap: 20px;
  min-height: 100%;
  padding-block: 24px;
  overflow-wrap: anywhere;
}
.kg-welcome {
  margin-block: auto;
  text-align: center;
}
.kg-welcome h2 {
  margin: 0 0 8px;
  font-size: 22px;
  font-weight: 600;
}
.kg-welcome p {
  max-width: 520px;
  margin: 0 auto 20px;
  font-size: 14px;
  line-height: 1.65;
  color: var(--el-text-color-secondary);
}
.kg-suggestions,
.kg-replies {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.kg-suggestions {
  justify-content: center;
}
.kg-suggestions button,
.kg-replies button {
  max-width: 100%;
  padding: 7px 14px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 18px;
  background: var(--el-bg-color);
  font-size: 13px;
  color: var(--el-text-color-regular);
  text-align: start;
}
.kg-replies button {
  padding: 4px 12px;
  font-size: 12.5px;
}
.kg-suggestions button:hover:not(:disabled),
.kg-replies button:hover {
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
}
.kg-result {
  display: flex;
  align-items: center;
  gap: 12px;
  width: min(100%, 420px);
  padding: 10px 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-fill-color-blank, var(--el-bg-color));
  text-align: start;
}
.kg-result:hover {
  border-color: var(--el-border-color);
  background: var(--el-fill-color-lighter);
}
.kg-result__icon {
  flex-shrink: 0;
  color: var(--el-color-primary);
}
.kg-result__text {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  gap: 2px;
}
.kg-result strong {
  font-size: 13.5px;
  font-weight: 600;
}
.kg-result small,
.kg-working,
.kg-note,
.kg-hint,
.kg-composer > small {
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
.kg-working,
.kg-note {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin: 0;
}
.kg-note--alert {
  color: var(--el-color-danger);
}
.kg-link {
  padding: 0;
  border: 0;
  background: none;
  font-size: inherit;
  color: var(--el-color-primary);
}
.kg-link:hover {
  text-decoration: underline;
}
.kg-dots {
  display: inline-flex;
  gap: 3px;
}
.kg-dots i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  animation: kg-dot 1.2s ease-in-out infinite;
}
.kg-dots i:nth-child(2) {
  animation-delay: 0.15s;
}
.kg-dots i:nth-child(3) {
  animation-delay: 0.3s;
}
@keyframes kg-dot {
  0%,
  80%,
  100% {
    opacity: 0.3;
  }
  40% {
    opacity: 1;
  }
}
.kg-composer {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-bottom: 12px;
}
.kg-composer :deep(.chat-input-wrapper) {
  padding: 0;
  border-top: 0;
}
.kg-composer > small {
  font-size: 11.5px;
  text-align: center;
}

/* Report and sources share one side panel; on narrow screens it covers the conversation. */
.kg-panel {
  display: flex;
  flex-direction: column;
  width: 400px;
  max-width: 45%;
  min-width: 0;
  border-inline-start: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
}
.kg-panel > header {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.kg-tab {
  height: 30px;
  padding: 0 12px;
  border: 0;
  border-radius: 8px;
  background: none;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.kg-tab[aria-pressed="true"] {
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
  font-weight: 600;
}
.kg-close {
  margin-inline-start: auto;
}
.kg-panel__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 12px 20px 24px;
  overflow-wrap: anywhere;
}
.kg-panel__body > .kg-hint {
  margin: 0 0 8px;
}
.kg-summary {
  margin: 8px 0 4px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
  font-size: 13px;
  line-height: 1.6;
}
.kg-issue {
  padding: 16px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.kg-issue:last-child {
  border-bottom: 0;
}
.kg-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 11.5px;
}
.kg-tags span {
  padding: 2px 8px;
  border-radius: 10px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
}
.kg-tags .kg-tag--pending {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
}
.kg-tags .kg-tag--confirmed {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
}
.kg-issue h3,
.kg-original h3 {
  margin: 10px 0 4px;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.5;
}
dl {
  margin: 0;
}
dt {
  margin-top: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
dd {
  margin: 2px 0 0;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
}
dd.kg-after {
  padding: 6px 10px;
  border-inline-start: 3px solid var(--el-color-primary);
  border-radius: 0 6px 6px 0;
  background: var(--el-color-primary-light-9);
}
.kg-source {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  width: 100%;
  margin-top: 6px;
  padding: 4px 6px;
  margin-inline-start: -6px;
  border: 0;
  border-radius: 6px;
  background: none;
  font-size: 12.5px;
  color: var(--el-color-primary);
  text-align: start;
}
.kg-source svg {
  flex-shrink: 0;
  margin-top: 2px;
}
.kg-source:hover,
.kg-source[aria-current="true"] {
  background: var(--el-fill-color-light);
}
.kg-original {
  margin-top: 16px;
  padding-top: 8px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.kg-original blockquote {
  margin: 8px 0;
  padding: 2px 0 2px 12px;
  border-inline-start: 3px solid var(--el-border-color);
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  color: var(--el-text-color-regular);
}
@media (max-width: 800px) {
  .kg-header {
    padding-inline: 8px;
  }
  .kg-ghost__label {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }
  .kg-ghost:not(.kg-ghost--icon) {
    padding: 0 8px;
  }
  .kg-thread,
  .kg-composer {
    padding-inline: 16px;
  }
  /* The covered conversation leaves the tab order and accessibility tree until the panel closes. */
  .kg-body--panel .kg-chat {
    visibility: hidden;
  }
  .kg-panel {
    position: absolute;
    inset: 0;
    z-index: 3;
    width: auto;
    max-width: none;
    border: 0;
  }
}
@media (prefers-reduced-motion: reduce) {
  .kg-dots i {
    animation: none;
  }
}
</style>
