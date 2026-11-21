import { watch } from "vue";
import { storeToRefs } from "pinia";
import { useDiagnosisChatStore } from "../stores/agent-chat";

export function truncateTitle(text: string, max = 30): string {
  return text.length > max ? `${text.slice(0, max)}...` : text;
}

export interface UseAgentChatOptions {
  onStreamUpdate?: () => void;
}

export function useAgentChat(options: UseAgentChatOptions = {}) {
  const store = useDiagnosisChatStore();
  const refs = storeToRefs(store);
  const stopWatching = options.onStreamUpdate
    ? watch(
        refs.messages,
        () => {
          options.onStreamUpdate?.();
        },
        { deep: true, flush: "post" }
      )
    : () => {};

  return {
    ...refs,
    loadSessions: store.loadSessions,
    createSession: store.createSession,
    deleteSession: store.deleteSession,
    selectSession: store.selectSession,
    clearSession: store.clearSession,
    sendMessage: store.sendMessage,
    stopSending: store.stopSending,
    cleanup: stopWatching,
  };
}
