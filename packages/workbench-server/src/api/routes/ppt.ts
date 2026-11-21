import { Request, Response, Router } from "express";
import { z } from "zod";
import { buildAttachmentContentDisposition, asyncHandler } from "../utils/http";
import { getPptDefaultInstructions, summarizeReportForPpt } from "../../services/analysis-agent/delivery/ppt-summarizer";

import { generatePpt } from "../../services/analysis-agent/delivery/ppt-generator";
import { pptArtifactStore, safePptFileName } from "../../services/analysis-agent/delivery/ppt-artifacts";
import { requireDomainId, type AuthenticatedRequest } from "../../utils/request-identity";

const router: ReturnType<typeof Router> = Router();

router.get("/defaults", (_req: Request, res: Response) => {
  res.json({ success: true, data: { instructions: getPptDefaultInstructions() } });
});

const PptSummaryBodySchema = z.object({
  title: z.string().trim().min(1).max(200),
  userInstructions: z.string().trim().max(10_000).optional().default(""),
  cover: z
    .object({ title: z.string().trim().max(200), subtitle: z.string().trim().max(500) })
    .optional(),
  report: z.string().trim().min(1).max(500_000),
  sections: z
    .array(
      z.object({
        id: z.string().min(1).max(200),
        heading: z.string().max(300),
        text: z.string().max(30_000),
        hasChart: z.boolean(),
      })
    )
    .min(1)
    .max(50),
});

router.post(
  "/summarize",
  asyncHandler(async (req: Request, res: Response) => {
    const input = PptSummaryBodySchema.parse(req.body);
    const outline = await summarizeReportForPpt(input);
    res.json({ success: true, data: outline });
  })
);

const PptExportSchema = z.object({
  title: z.string().trim().min(1).max(200),
  sections: z.array(z.object({id: z.string().min(1).max(200), heading: z.string().max(300).optional(), content: z.string().max(500000)})).min(1).max(500),
  userInstructions: z.string().max(10000).optional(),
  cover: z.object({title: z.string().max(200), subtitle: z.string().max(500)}).optional(),
  coverImage: z.string().regex(/^data:image\/(?:png|jpeg|webp);base64,[A-Za-z0-9+/=]+$/).max(15000000).optional(),
  coverImageMode: z.enum(["background", "side"]).optional(),
});

router.post("/export", asyncHandler(async (req: Request, res: Response) => {
  const {title, sections, ...options} = PptExportSchema.parse(req.body);
  const controller = new AbortController();
  const cancel = () => { if (!res.writableEnded) controller.abort(); };
  res.on("close", cancel);
  try {
    const {buffer, warnings} = await generatePpt(title, sections, options, controller.signal);
    res.setHeader("Content-Type", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    res.setHeader("Content-Disposition", buildAttachmentContentDisposition(safePptFileName(title)));
    if (warnings.length) res.setHeader("X-Ppt-Warning-Count", String(warnings.length));
    res.send(buffer);
  } finally { res.off("close", cancel); }
}));

router.get("/files/:id", asyncHandler(async (req: Request, res: Response) => {
  const identity = req as AuthenticatedRequest;
  const artifact = await pptArtifactStore().read(req.params.id, {domainId: requireDomainId(identity), userId: identity.userId});
  if (!artifact) { res.status(404).json({success: false, error: "PPT file not found"}); return; }
  res.setHeader("Cache-Control", "private, no-store");
  res.setHeader("Content-Type", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
  res.setHeader("Content-Disposition", buildAttachmentContentDisposition(artifact.fileName));
  res.send(artifact.buffer);
}));

export default router;
