import { Router } from "express";
import { z } from "zod";
import { governanceMessageInputSchema } from "@ontomato/contracts/knowledge-governance";
import { governanceRuntime } from "../../services/knowledge-governance/runtime";
import {
  governanceStore,
  publicGovernanceSession,
} from "../../services/knowledge-governance/store";
import { readGovernanceSource } from "../../services/knowledge-governance/tools";
import { requireDomainId, type AuthenticatedRequest } from "../../utils/request-identity";
import { HttpError } from "../../utils/errors";
import { subscribeResponseStream, writeUnavailableStream } from "../../utils/sse";
import { requirePermission } from "../../identity/installed";
import { asyncHandler } from "../utils/http";

const router: ReturnType<typeof Router> = Router();
router.use(requirePermission(["business-knowledge"]));
function context(req: AuthenticatedRequest) {
  const domainId = requireDomainId(req);
  return {
    owner: { domainId, userId: req.userId },
    credentials: { domainId, token: req.token, apiKey: req.apiKey },
    locale: req.locale,
  };
}
function messageInput(body: unknown) {
  const input = governanceMessageInputSchema.safeParse(body);
  if (!input.success)
    throw new HttpError(400, "A non-empty message up to 20000 characters is required");
  return input.data.message;
}
function sessionId(value: string) {
  const result = z.string().uuid().safeParse(value);
  if (!result.success) throw new HttpError(400, "Invalid session id");
  return result.data;
}
router.get(
  "/",
  asyncHandler(async (req, res) => {
    res.json(await governanceStore.list(context(req as AuthenticatedRequest).owner));
  })
);
router.post(
  "/",
  asyncHandler(async (req, res) => {
    const message = messageInput(req.body);
    const { owner, credentials, locale } = context(req as AuthenticatedRequest);
    res.status(201).json(await governanceRuntime.start(owner, credentials, locale, message));
  })
);
router.get(
  "/:id",
  asyncHandler(async (req, res) => {
    res.json(
      publicGovernanceSession(
        await governanceStore.get(
          sessionId(req.params.id!),
          context(req as AuthenticatedRequest).owner
        )
      )
    );
  })
);
router.post(
  "/:id/messages",
  asyncHandler(async (req, res) => {
    const message = messageInput(req.body);
    const { owner, credentials, locale } = context(req as AuthenticatedRequest);
    res.json(
      await governanceRuntime.respond(
        sessionId(req.params.id!),
        owner,
        credentials,
        locale,
        message
      )
    );
  })
);
// The turn id comes from the snapshot, so a resumed cursor only ever applies to its own turn.
// Closing this subscription only detaches the page; Stop is the explicit cancel.
router.get(
  "/:id/turns/:turnId/stream",
  asyncHandler(async (req, res) => {
    const stream = await governanceRuntime.responseStream(
      sessionId(req.params.id!),
      context(req as AuthenticatedRequest).owner,
      req.params.turnId!
    );
    if (stream) subscribeResponseStream(req, res, stream);
    else writeUnavailableStream(res);
  })
);
router.post(
  "/:id/stop",
  asyncHandler(async (req, res) => {
    res.json(
      await governanceRuntime.stop(
        sessionId(req.params.id!),
        context(req as AuthenticatedRequest).owner
      )
    );
  })
);
router.get(
  "/:id/sources/:sourceId",
  asyncHandler(async (req, res) => {
    const { owner, credentials } = context(req as AuthenticatedRequest);
    const offset = z.coerce
      .number()
      .int()
      .nonnegative()
      .safeParse(req.query.offset || 0);
    if (!offset.success) throw new HttpError(400, "Invalid source offset");
    const record = await governanceStore.get(sessionId(req.params.id!), owner);
    res.json(await readGovernanceSource(record, credentials, req.params.sourceId!, offset.data));
  })
);
export default router;
