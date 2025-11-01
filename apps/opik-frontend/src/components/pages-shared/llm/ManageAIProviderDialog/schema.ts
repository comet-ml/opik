import { z } from "zod";
import uniq from "lodash/uniq";

import { PROVIDER_TYPE } from "@/types/providers";

export const CloudAIProviderDetailsFormSchema = z.object({
  provider: z.enum(
    Object.values(PROVIDER_TYPE).filter(
      (v) => v !== PROVIDER_TYPE.VERTEX_AI && v !== PROVIDER_TYPE.CUSTOM,
    ) as [string, ...string[]],
    {
      message: "Provider is required",
    },
  ),
  apiKey: z
    .string({
      required_error: "API key is required",
    })
    .min(1, { message: "API key is required" }),
});

export const VertexAIProviderDetailsFormSchema = z.object({
  provider: z.enum([PROVIDER_TYPE.VERTEX_AI], {
    message: "Provider is required",
  }),
  apiKey: z
    .string({
      required_error: "API key is required",
    })
    .min(1, { message: "API key is required" }),
  location: z.string(),
});

export const CustomProviderDetailsFormSchema = z.object({
  provider: z.enum([PROVIDER_TYPE.CUSTOM], {
    message: "Provider is required",
  }),
  providerName: z
    .string({
      required_error: "Provider name is required",
    })
    .min(1, { message: "Provider name is required" }),
  apiKey: z.string(),
  url: z.string().url(),
  models: z
    .string()
    .min(1, { message: "Models is required" })
    .refine(
      (models) => {
        const modelsArray = models.split(",").map((m) => m.trim());

        return modelsArray.length === uniq(modelsArray).length;
      },
      { message: "All model names should be unique" },
    ),
});

// Schema for editing existing custom providers (uses provider ID instead of type)
export const CustomProviderEditFormSchema = z.object({
  provider: z.string().uuid(), // Provider ID for existing custom providers
  providerName: z
    .string({
      required_error: "Provider name is required",
    })
    .min(1, { message: "Provider name is required" }),
  apiKey: z.string(),
  url: z.string().url(),
  models: z
    .string()
    .min(1, { message: "Models is required" })
    .refine(
      (models) => {
        const modelsArray = models.split(",").map((m) => m.trim());

        return modelsArray.length === uniq(modelsArray).length;
      },
      { message: "All model names should be unique" },
    ),
});

export const AIProviderFormSchema = z.union([
  CloudAIProviderDetailsFormSchema,
  VertexAIProviderDetailsFormSchema,
  CustomProviderDetailsFormSchema,
  CustomProviderEditFormSchema,
]);

export type AIProviderFormType = z.infer<typeof AIProviderFormSchema>;
