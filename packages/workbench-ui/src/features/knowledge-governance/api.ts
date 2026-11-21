import { z } from "zod";
import {
  governanceSessionSchema,
  governanceSummarySchema,
  governanceSourceReadSchema,
} from "@ontomato/contracts/knowledge-governance";
import { nodeApiFetch, nodeApiGet, nodeApiPost } from "../../utils/api";

const base = "/knowledge-governance";
export const governanceApi = {
  async list(signal?: AbortSignal) {
    return z.array(governanceSummarySchema).parse(await nodeApiGet(base, { signal }));
  },
  async read(id: string, signal?: AbortSignal) {
    return governanceSessionSchema.parse(
      await nodeApiGet(`${base}/${encodeURIComponent(id)}`, { signal })
    );
  },
  /** Creates the round with its first user message and starts the response. */
  async start(message: string, signal?: AbortSignal) {
    return governanceSessionSchema.parse(await nodeApiPost(base, { message }, { signal }));
  },
  async respond(id: string, message: string, signal?: AbortSignal) {
    return governanceSessionSchema.parse(
      await nodeApiPost(`${base}/${encodeURIComponent(id)}/messages`, { message }, { signal })
    );
  },
  /** Subscribes to one running turn, resuming after `lastSeq`; closing it never stops the turn. */
  stream(id: string, turnId: string, lastSeq: number, signal: AbortSignal) {
    const path = `${base}/${encodeURIComponent(id)}/turns/${encodeURIComponent(turnId)}/stream`;
    return nodeApiFetch(path, "GET", undefined, {
      signal,
      headers: lastSeq > 0 ? { "Last-Event-ID": String(lastSeq) } : {},
    });
  },
  async stop(id: string, signal?: AbortSignal) {
    await nodeApiPost(`${base}/${encodeURIComponent(id)}/stop`, {}, { signal });
  },
  async source(id: string, sourceId: string, offset: number, signal?: AbortSignal) {
    return governanceSourceReadSchema.parse(
      await nodeApiGet(
        `${base}/${encodeURIComponent(id)}/sources/${encodeURIComponent(sourceId)}?offset=${offset}`,
        { signal }
      )
    );
  },
};
