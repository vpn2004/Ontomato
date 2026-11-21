import { z } from "zod";
import { agentChatMessageSchema, agentResponseEventSchema } from "./agent-chat";

export const governanceSourceSchema = z.object({
  id: z.string().min(1),
  kind: z.enum(["knowledge", "ontology", "history", "artifact"]),
  target: z.string(),
  title: z.string(),
  excerpt: z.string(),
});

export const governanceIssueSchema = z.object({
  id: z.string().min(1),
  title: z.string().min(1),
  category: z.enum(["ontology", "knowledge", "data"]),
  status: z.enum(["proposed", "pending", "confirmed"]),
  problem: z.string().min(1),
  before: z.string(),
  after: z.string().min(1),
  basis: z.string().min(1),
  sourceIds: z.array(z.string()),
});

export const governanceWorkSchema = z.object({
  summary: z.string(),
  notes: z.string(),
  reviewedKnowledgeIds: z.array(z.string()),
  knowledgeTotal: z.number().int().nonnegative().nullable(),
  issues: z.array(governanceIssueSchema),
  sources: z.array(governanceSourceSchema),
});

export const governanceSessionSchema = z.object({
  id: z.string().uuid(),
  domainId: z.string().min(1),
  initiatorId: z.string().min(1),
  createdAt: z.string(),
  updatedAt: z.string(),
  revision: z.number().int().nonnegative(),
  status: z.enum(["idle", "running", "stopped", "failed"]),
  /** Harness turn of a running round; its response stream is addressed by this id. */
  activeTurnId: z.string().min(1).nullable(),
  /** Settled Harness messages; the running turn arrives only through the response stream. */
  messages: z.array(agentChatMessageSchema),
  error: z.string().nullable(),
  work: governanceWorkSchema,
});

export const governanceSummarySchema = governanceSessionSchema.pick({
  id: true,
  createdAt: true,
  updatedAt: true,
  status: true,
});

export type GovernanceSource = z.infer<typeof governanceSourceSchema>;
export type GovernanceIssue = z.infer<typeof governanceIssueSchema>;
export type GovernanceWork = z.infer<typeof governanceWorkSchema>;
export type GovernanceSession = z.infer<typeof governanceSessionSchema>;
export type GovernanceSummary = z.infer<typeof governanceSummarySchema>;

export const governanceSourceReadSchema = z.object({
  source: governanceSourceSchema,
  page: z
    .object({
      items: z.array(
        z.object({
          id: z.string(),
          title: z.string(),
          text: z.string(),
          start: z.number(),
          end: z.number(),
          totalChars: z.number(),
        })
      ),
      totalItems: z.number(),
      matchedItems: z.number(),
      next: z.object({ index: z.number(), offset: z.number() }).nullable(),
    })
    .nullable(),
});
export type GovernanceSourceRead = z.infer<typeof governanceSourceReadSchema>;

export const governanceMessageInputSchema = z.object({
  message: z.string().trim().min(1).max(20_000),
});

/** Response stream of one governance turn: shared chat events plus saved business work. */
export const governanceStreamEventSchema = z.union([
  agentResponseEventSchema,
  z.object({ type: z.literal("work"), work: governanceWorkSchema }),
]);
export type GovernanceStreamEvent = z.infer<typeof governanceStreamEventSchema>;
