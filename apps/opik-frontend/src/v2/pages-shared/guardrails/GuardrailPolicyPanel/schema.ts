import { z } from "zod";

import { GuardrailPolicyPayload } from "@/api/guardrail-policies/useGuardrailPolicyCreateMutation";
import {
  GUARDRAIL_EXECUTION_SCOPE,
  GuardrailPolicy,
  GuardrailTypes,
} from "@/types/guardrails";
import { GUARDRAIL_RULE_TYPES_ORDER } from "@/constants/guardrails";
import {
  DEFAULT_RULES_STATE,
  toRulesPayload,
  toRulesState,
  validateRules,
} from "@/v2/pages-shared/guardrails/GuardrailPolicyPanel/rulesState";

/** The backend caps the name at 255 characters, so the field does too. */
export const POLICY_NAME_MAX_LENGTH = 255;
export const POLICY_DESCRIPTION_MAX_LENGTH = 1000;

export const NO_RULE_MESSAGE =
  "A policy needs at least one rule. Switch on the checks it should run.";

// Plain keys rather than the GuardrailTypes members: form paths such as "rules.PII.enabled" are
// strings, and react-hook-form cannot resolve their value types through enum-typed keys. The
// property names are the same, so RulesState still fits (toFormValues checks it).
const RulesStateSchema = z.object({
  PII: z.object({
    enabled: z.boolean(),
    entities: z.array(z.string()),
  }),
  TOPIC: z.object({
    enabled: z.boolean(),
    restrictedTopics: z.array(z.string()),
    allowedTopics: z.array(z.string()),
  }),
  PROMPT_INJECTION: z.object({
    enabled: z.boolean(),
  }),
});

export const GuardrailPolicyFormSchema = z
  .object({
    name: z
      .string()
      .trim()
      .min(1, "Name is required")
      .max(POLICY_NAME_MAX_LENGTH),
    description: z.string().max(POLICY_DESCRIPTION_MAX_LENGTH),
    executionScope: z.nativeEnum(GUARDRAIL_EXECUTION_SCOPE),
    rules: RulesStateSchema,
  })
  .superRefine(({ rules }, ctx) => {
    if (!GUARDRAIL_RULE_TYPES_ORDER.some((type) => rules[type].enabled)) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: NO_RULE_MESSAGE,
        path: ["rules"],
      });
      return;
    }

    // Each message sits on the control it is about; the topic one goes under the first list.
    const errors = validateRules(rules);
    if (errors["pii.entities"]) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: errors["pii.entities"],
        path: ["rules", GuardrailTypes.PII, "entities"],
      });
    }
    if (errors["topic.topics"]) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: errors["topic.topics"],
        path: ["rules", GuardrailTypes.TOPIC, "restrictedTopics"],
      });
    }
  });

export type GuardrailPolicyFormValues = z.infer<
  typeof GuardrailPolicyFormSchema
>;

export const toFormValues = (
  policy: GuardrailPolicy | undefined,
  name: string,
): GuardrailPolicyFormValues => ({
  name,
  description: policy?.description ?? "",
  executionScope:
    policy?.execution_scope ?? GUARDRAIL_EXECUTION_SCOPE.ON_REQUEST,
  rules: policy ? toRulesState(policy.rules) : DEFAULT_RULES_STATE,
});

export const toPolicyPayload = (
  values: GuardrailPolicyFormValues,
): GuardrailPolicyPayload => ({
  name: values.name.trim(),
  ...(values.description.trim() && {
    description: values.description.trim(),
  }),
  execution_scope: values.executionScope,
  rules: toRulesPayload(values.rules),
});
