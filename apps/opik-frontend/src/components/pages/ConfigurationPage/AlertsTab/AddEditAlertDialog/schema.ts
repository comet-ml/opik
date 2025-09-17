import { z } from "zod";

export const HeaderSchema = z.object({
  key: z.string().min(1, { message: "Header key is required" }),
  value: z.string().min(1, { message: "Header value is required" }),
});

const EventTriggersObjectSchema = z.object({
  traceErrorNewError: z.boolean().default(false),
  traceErrorScope: z.array(z.string()).default([]),
  traceErrorScopeToggle: z.boolean().default(true),

  guardrailTriggered: z.boolean().default(false),
  guardrailScope: z.array(z.string()).default([]),
  guardrailScopeToggle: z.boolean().default(true),

  feedbackScoreNewTrace: z.boolean().default(false),
  feedbackScoreNewThread: z.boolean().default(false),
  feedbackScoreScope: z.array(z.string()).default([]),
  feedbackScoreScopeToggle: z.boolean().default(true),

  promptLibraryNewPrompt: z.boolean().default(false),
  promptLibraryNewCommit: z.boolean().default(false),
});

export const AlertFormSchema = z
  .object({
    name: z
      .string({ required_error: "Alert name is required" })
      .min(1, { message: "Alert name is required" }),
    enabled: z.boolean().default(true),
    url: z
      .string({ required_error: "Endpoint URL is required" })
      .min(1, { message: "Endpoint URL is required" })
      .url({ message: "Please enter a valid URL" }),
    secretToken: z.string().optional(),
    headers: z.array(HeaderSchema).default([]),
    eventTriggers: EventTriggersObjectSchema,
  })
  .refine(
    (data) => {
      // At least one event must be enabled
      const eventTriggers = data.eventTriggers;
      return (
        eventTriggers.traceErrorNewError ||
        eventTriggers.guardrailTriggered ||
        eventTriggers.feedbackScoreNewTrace ||
        eventTriggers.feedbackScoreNewThread ||
        eventTriggers.promptLibraryNewPrompt ||
        eventTriggers.promptLibraryNewCommit
      );
    },
    {
      message: "At least one event must be selected",
      path: ["eventTriggers"],
    },
  );

export type AlertFormType = z.infer<typeof AlertFormSchema>;
