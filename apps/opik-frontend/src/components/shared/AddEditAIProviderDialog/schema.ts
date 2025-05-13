import { z } from "zod";
import { PROVIDER_LOCATION_TYPE, PROVIDER_TYPE } from "@/types/providers";
import uniq from "lodash/uniq";

const ProviderSchema = z
  .union([z.nativeEnum(PROVIDER_TYPE), z.string().length(0)], {
    required_error: "Provider is required",
  })
  .refine((model) => model.length >= 1, { message: "Provider is required" });

export const CloudAIProviderDetailsFormSchema = z.object({
  provider: ProviderSchema,
  locationType: z.literal(PROVIDER_LOCATION_TYPE.cloud),
  apiKey: z
    .string({
      required_error: "API key is required",
    })
    .min(1, { message: "API key is required" }),
});

export const LocalAIProviderDetailsFormSchema = z.object({
  provider: ProviderSchema,
  locationType: z.literal(PROVIDER_LOCATION_TYPE.local),
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

export const VertexAIProviderDetailsFormSchema = z.object({
  provider: ProviderSchema,
  locationType: z.literal(PROVIDER_LOCATION_TYPE.cloud),
  apiKey: z
    .string({
      required_error: "API key is required",
    })
    .min(1, { message: "API key is required" }),
  location: z.string(),
});

export const AIProviderFormSchema = z.union([
  CloudAIProviderDetailsFormSchema,
  VertexAIProviderDetailsFormSchema,
  LocalAIProviderDetailsFormSchema,
]);

export type AIProviderFormType = z.infer<typeof AIProviderFormSchema>;
