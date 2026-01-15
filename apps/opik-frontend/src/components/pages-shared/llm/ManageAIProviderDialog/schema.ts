import { z } from "zod";
import uniq from "lodash/uniq";

import { PROVIDER_TYPE } from "@/types/providers";

export const CloudAIProviderDetailsFormSchema = z.object({
  provider: z.enum(
    Object.values(PROVIDER_TYPE).filter(
      (v) =>
        v !== PROVIDER_TYPE.VERTEX_AI &&
        v !== PROVIDER_TYPE.CUSTOM &&
        v !== PROVIDER_TYPE.BEDROCK,
    ) as [string, ...string[]],
    {
      message: "Provider is required",
    },
  ),
  composedProviderType: z.string(),
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
  composedProviderType: z.string(),
  apiKey: z
    .string({
      required_error: "API key is required",
    })
    .min(1, { message: "API key is required" }),
  location: z.string(),
});

export const createCustomProviderDetailsFormSchema = (
  existingProviderNames?: string[],
) =>
  z
    .object({
      provider: z.enum([PROVIDER_TYPE.CUSTOM, PROVIDER_TYPE.BEDROCK], {
        message: "Provider is required",
      }),
      composedProviderType: z.string(),
      id: z.string().optional(),
      providerName: z.string().optional(),
      apiKey: z.string(),
      url: z.string().url(),
      models: z
        .string()
        .min(1, { message: "Models list is required" })
        .refine(
          (models) => {
            const modelsArray = models.split(",").map((m) => m.trim());

            return modelsArray.length === uniq(modelsArray).length;
          },
          { message: "All model names should be unique" },
        ),
      headers: z
        .array(
          z.object({
            key: z.string(),
            value: z.string(),
            id: z.string(),
          }),
        )
        .optional(),
    })
    .superRefine((data, ctx) => {
      // Validate headers: if a header has any content, both key and value must be non-empty
      if (data.headers) {
        const headerKeys: string[] = [];

        data.headers.forEach((header, index) => {
          const hasKey = header.key.trim().length > 0;
          const hasValue = header.value.trim().length > 0;

          // If either field has content, both must have content
          if ((hasKey || hasValue) && !hasKey) {
            ctx.addIssue({
              code: z.ZodIssueCode.custom,
              message: "Header key is required",
              path: ["headers", index, "key"],
            });
          }

          if ((hasKey || hasValue) && !hasValue) {
            ctx.addIssue({
              code: z.ZodIssueCode.custom,
              message: "Header value is required",
              path: ["headers", index, "value"],
            });
          }

          // Check for duplicate header keys
          if (hasKey) {
            const trimmedKey = header.key.trim();
            if (headerKeys.includes(trimmedKey)) {
              ctx.addIssue({
                code: z.ZodIssueCode.custom,
                message: "Header key must be unique",
                path: ["headers", index, "key"],
              });
            } else {
              headerKeys.push(trimmedKey);
            }
          }
        });
      }
      if (!data.id && (!data.providerName || data.providerName.length === 0)) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: "Provider name is required",
          path: ["providerName"],
        });
      }

      if (
        !data.id &&
        data.providerName &&
        existingProviderNames?.includes(data.providerName)
      ) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: "Provider name already exists",
          path: ["providerName"],
        });
      }
    });

export const createAIProviderFormSchema = (existingProviderNames?: string[]) =>
  z.union([
    CloudAIProviderDetailsFormSchema,
    VertexAIProviderDetailsFormSchema,
    createCustomProviderDetailsFormSchema(existingProviderNames),
  ]);

export const AIProviderFormSchema = createAIProviderFormSchema();

export type AIProviderFormType = z.infer<typeof AIProviderFormSchema>;
