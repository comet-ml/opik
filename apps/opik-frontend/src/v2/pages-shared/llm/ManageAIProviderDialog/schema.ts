import { z } from "zod";
import uniq from "lodash/uniq";

import {
  AUTH_SEND_AS_VALUES,
  OPENAI_PIPELINE_MODE_VALUES,
  OpenAiPipelineMode,
  PROVIDER_TYPE,
} from "@/types/providers";
import { AUTH_MODE_VALUES } from "./customProviderConfig";

export type { OpenAiPipelineMode };
export { OPENAI_PIPELINE_MODE_VALUES };

// Default pipeline mode applied as a fallback in form defaults, resets, and save payloads.
// Centralised here so changing the default requires editing only one place.
export const DEFAULT_OPENAI_PIPELINE_MODE: OpenAiPipelineMode =
  "chat_completions_api";

/**
 * Normalises a backend-stored {@code openai_pipeline_mode} string into a typed
 * {@link OpenAiPipelineMode}. The backend's {@code OpenAIClientGenerator.extractApiPipelineMode}
 * accepts any casing (it uppercases before enum lookup), so the persisted value could be either
 * lowercase or uppercase depending on how it was written (UI vs direct REST). The form schema is
 * strict-cased lowercase, so we lowercase here and reject anything that isn't one of the known
 * values — falling back to {@link DEFAULT_OPENAI_PIPELINE_MODE}. Keeps the Select always pointing
 * at a valid option and prevents Zod from blocking submit on legacy/odd-cased values.
 */
export const normalizeOpenAiPipelineMode = (
  value: string | undefined | null,
): OpenAiPipelineMode => {
  if (!value) return DEFAULT_OPENAI_PIPELINE_MODE;
  const lowered = value.toLowerCase();
  return (OPENAI_PIPELINE_MODE_VALUES as readonly string[]).includes(lowered)
    ? (lowered as OpenAiPipelineMode)
    : DEFAULT_OPENAI_PIPELINE_MODE;
};

export const CloudAIProviderDetailsFormSchema = z.object({
  provider: z.enum(
    Object.values(PROVIDER_TYPE).filter(
      (v) =>
        v !== PROVIDER_TYPE.VERTEX_AI &&
        v !== PROVIDER_TYPE.CUSTOM &&
        v !== PROVIDER_TYPE.BEDROCK &&
        v !== PROVIDER_TYPE.OLLAMA,
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
  // OpenAI-only: which pipeline the backend routes the request through. Schema-level optional
  // because non-OpenAI cloud providers ignore it. The dialog defaults to chat_completions_api.
  openaiPipelineMode: z.enum(OPENAI_PIPELINE_MODE_VALUES).optional(),
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
      provider: z.enum(
        [PROVIDER_TYPE.CUSTOM, PROVIDER_TYPE.BEDROCK, PROVIDER_TYPE.OLLAMA],
        {
          message: "Provider is required",
        },
      ),
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
      queryParams: z
        .array(
          z.object({
            key: z.string(),
            value: z.string(),
            id: z.string(),
          }),
        )
        .optional(),
      authHeaderName: z.string().max(150).optional(),
      suppressDefaultAuth: z.boolean().optional(),
      authMode: z.enum(AUTH_MODE_VALUES).optional(),
      authTokenUrl: z.string().optional(),
      authSendAs: z.enum(AUTH_SEND_AS_VALUES).optional(),
      authCredentials: z
        .array(
          z.object({
            // max mirrors the backend's @Size(max = 250) on Credential.key
            key: z.string().max(250),
            value: z.string(),
            secret: z.boolean(),
            saved: z.boolean(),
            id: z.string(),
          }),
        )
        .optional(),
      authTokenField: z.string().max(250).optional(),
      authExpiresField: z.string().max(250).optional(),
      authFallbackTtl: z.string().optional(),
    })
    .superRefine((data, ctx) => {
      // Token-auth mode requirements: field-level rules stay optional because static mode is the
      // default and none of these apply there.
      if (data.authMode === "token") {
        const tokenUrl = (data.authTokenUrl ?? "").trim();
        if (!tokenUrl || !z.string().url().safeParse(tokenUrl).success) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: "Token URL must be a valid URL",
            path: ["authTokenUrl"],
          });
        }

        const credentials = data.authCredentials ?? [];
        if (!credentials.some((entry) => entry.key.trim().length > 0)) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: "At least one credential is required",
            path: ["authTokenUrl"],
          });
        }

        const credentialKeys: string[] = [];
        credentials.forEach((entry, index) => {
          const hasKey = entry.key.trim().length > 0;
          if (!hasKey && entry.value.trim().length > 0) {
            ctx.addIssue({
              code: z.ZodIssueCode.custom,
              message: "Credential key is required",
              path: ["authCredentials", index, "key"],
            });
          }
          if (hasKey) {
            const trimmedKey = entry.key.trim();
            if (credentialKeys.includes(trimmedKey)) {
              ctx.addIssue({
                code: z.ZodIssueCode.custom,
                message: "Credential key must be unique",
                path: ["authCredentials", index, "key"],
              });
            } else {
              credentialKeys.push(trimmedKey);
            }
          }
        });

        const fallbackTtl = (data.authFallbackTtl ?? "").trim();
        // digits-only implies non-negative; no separate sign/number check needed
        if (fallbackTtl.length > 0 && !/^\d+$/.test(fallbackTtl)) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: "Fallback lifetime must be a whole number of seconds",
            path: ["authFallbackTtl"],
          });
        }
      }
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

      // Validate query params: same rules as headers (both key/value required, unique keys)
      if (data.queryParams) {
        const paramKeys: string[] = [];

        data.queryParams.forEach((param, index) => {
          const hasKey = param.key.trim().length > 0;
          const hasValue = param.value.trim().length > 0;

          if ((hasKey || hasValue) && !hasKey) {
            ctx.addIssue({
              code: z.ZodIssueCode.custom,
              message: "Query parameter key is required",
              path: ["queryParams", index, "key"],
            });
          }

          if ((hasKey || hasValue) && !hasValue) {
            ctx.addIssue({
              code: z.ZodIssueCode.custom,
              message: "Query parameter value is required",
              path: ["queryParams", index, "value"],
            });
          }

          if (hasKey) {
            const trimmedKey = param.key.trim();
            if (paramKeys.includes(trimmedKey)) {
              ctx.addIssue({
                code: z.ZodIssueCode.custom,
                message: "Query parameter key must be unique",
                path: ["queryParams", index, "key"],
              });
            } else {
              paramKeys.push(trimmedKey);
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
