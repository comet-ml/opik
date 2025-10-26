import { z } from "zod";
import { ALERT_EVENT_TYPE, ALERT_TYPE } from "@/types/alerts";

export const HeaderSchema = z.object({
  key: z.string().min(1, { message: "Header key is required" }),
  value: z.string().min(1, { message: "Header value is required" }),
});

export const TriggerSchema = z.object({
  eventType: z.nativeEnum(ALERT_EVENT_TYPE),
  projectIds: z.array(z.string()).default([]),
});

export const AlertFormSchema = z
  .object({
    name: z
      .string({ required_error: "Alert name is required" })
      .min(1, { message: "Alert name is required" }),
    enabled: z.boolean().default(true),
    alertType: z.nativeEnum(ALERT_TYPE).default(ALERT_TYPE.general),
    routingKey: z.string().optional(),
    url: z
      .string({ required_error: "Endpoint URL is required" })
      .min(1, { message: "Endpoint URL is required" })
      .url({ message: "Please enter a valid URL" }),
    secretToken: z.string().optional(),
    headers: z.array(HeaderSchema).default([]),
    triggers: z.array(TriggerSchema).default([]),
  })
  .refine(
    (data) => {
      return data.triggers.length > 0;
    },
    {
      message: "At least one trigger must be selected",
      path: ["triggers"],
    },
  )
  .refine(
    (data) => {
      // If alert type is PagerDuty, routing_key is required
      if (data.alertType === ALERT_TYPE.pagerduty) {
        return data.routingKey && data.routingKey.trim().length > 0;
      }
      return true;
    },
    {
      message: "Routing key is required for PagerDuty integration",
      path: ["routingKey"],
    },
  );

export type AlertFormType = z.infer<typeof AlertFormSchema>;
export type TriggerFormType = z.infer<typeof TriggerSchema>;
