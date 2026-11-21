import { measureStage, type StageTiming } from "../../../../logging/stage-timing";
import type { GraphState, GraphUpdate } from "../state";

export function timedNode(
  name: string,
  node: (state: GraphState, config?: any) => Promise<GraphUpdate>,
  onFinish?: (name: string, timing: StageTiming) => void
) {
  return (state: GraphState, config?: any): Promise<GraphUpdate> => measureStage(
    `workflow.${name}`,
    () => node(state, config),
    {
      signal: config?.signal ?? config?.configurable?.signal,
      metadata: {
        threadId: config?.configurable?.thread_id,
        requestSeq: config?.configurable?.requestSeq,
      },
      classify: (update) => update.errors?.length ? "failed" : "completed",
      onFinish: (timing) => onFinish?.(name, timing),
    }
  );
}
