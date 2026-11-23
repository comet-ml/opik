import {
  Fingerprint,
  MessagesSquare,
  ShieldAlert,
  type LucideIcon,
} from "lucide-react";

import { TagProps } from "@/ui/tag";
import {
  GUARDRAIL_EXECUTION_SCOPE,
  GuardrailComputedResult,
  GuardrailPolicyRuleType,
  GuardrailResult,
  GuardrailTypes,
  GuardrailValidation,
  PiiSupportedEntities,
} from "@/types/guardrails";

export const GuardrailNamesLabelMap = {
  [GuardrailTypes.TOPIC]: "Topic guardrail",
  [GuardrailTypes.PII]: "PII guardrail",
  [GuardrailTypes.LLM_JUDGE]: "LLM judge guardrail",
  [GuardrailTypes.PROMPT_INJECTION]: "Prompt injection guardrail",
  [GuardrailTypes.CUSTOM_CLASSIFIER]: "Custom guardrail",
};

export const getGuardrailComputedResult = (
  guardrails: GuardrailValidation[],
) => {
  const uniqueNames = new Set<GuardrailTypes>();
  guardrails.forEach((item) => {
    item.checks.forEach((check) => {
      uniqueNames.add(check.name);
    });
  });

  const result = {} as Record<GuardrailTypes, GuardrailResult>;

  uniqueNames.forEach((name) => {
    result[name] = GuardrailResult.PASSED;

    for (const item of guardrails) {
      for (const check of item.checks) {
        if (check.name === name && check.result === GuardrailResult.FAILED) {
          result[name] = GuardrailResult.FAILED;
          break;
        }
      }
      if (result[name] === GuardrailResult.FAILED) break;
    }
  });

  const statusList = Object.entries(result).map(([name, status]) => ({
    name,
    status,
  })) as GuardrailComputedResult[];

  let generalStatus = GuardrailResult.PASSED;
  const hasFailedGuardrails = statusList.some(
    (res) => res.status === GuardrailResult.FAILED,
  );
  if (hasFailedGuardrails) {
    generalStatus = GuardrailResult.FAILED;
  }

  return { statusList, generalStatus };
};

/**
 * The rule types a policy can hold, in the order the policy table and the policy form show them,
 * whatever order they are stored in.
 */
export const GUARDRAIL_RULE_TYPES_ORDER: GuardrailPolicyRuleType[] = [
  GuardrailTypes.PII,
  GuardrailTypes.TOPIC,
  GuardrailTypes.PROMPT_INJECTION,
];

export const GuardrailRuleTypeLabelMap: Record<
  GuardrailPolicyRuleType,
  string
> = {
  [GuardrailTypes.PII]: "PII",
  [GuardrailTypes.TOPIC]: "Topic",
  [GuardrailTypes.PROMPT_INJECTION]: "Prompt injection",
};

export const GuardrailRuleTypeIconMap: Record<
  GuardrailPolicyRuleType,
  LucideIcon
> = {
  [GuardrailTypes.PII]: Fingerprint,
  [GuardrailTypes.TOPIC]: MessagesSquare,
  [GuardrailTypes.PROMPT_INJECTION]: ShieldAlert,
};

export const GuardrailRuleTypeTagVariantMap: Record<
  GuardrailPolicyRuleType,
  TagProps["variant"]
> = {
  [GuardrailTypes.PII]: "purple",
  [GuardrailTypes.TOPIC]: "blue",
  [GuardrailTypes.PROMPT_INJECTION]: "red",
};

export const GuardrailRuleTypeDescriptionMap: Record<
  GuardrailPolicyRuleType,
  string
> = {
  [GuardrailTypes.PII]:
    "Detects personal data such as names, email addresses, phone numbers and card numbers.",
  [GuardrailTypes.TOPIC]:
    "Checks whether the text is about the topics you allow, or about topics you restrict.",
  [GuardrailTypes.PROMPT_INJECTION]:
    "Detects attempts to manipulate the assistant, such as prompt injection and jailbreaks.",
};

export const GuardrailExecutionScopeLabelMap: Record<
  GUARDRAIL_EXECUTION_SCOPE,
  string
> = {
  [GUARDRAIL_EXECUTION_SCOPE.ON_REQUEST]: "On request",
  [GUARDRAIL_EXECUTION_SCOPE.GLOBAL]: "Global",
};

export const GuardrailExecutionScopeDescriptionMap: Record<
  GUARDRAIL_EXECUTION_SCOPE,
  string
> = {
  [GUARDRAIL_EXECUTION_SCOPE.ON_REQUEST]:
    "Applies only when your code names this policy.",
  [GUARDRAIL_EXECUTION_SCOPE.GLOBAL]:
    "Applies to every other policy check in the workspace.",
};

/** Keyed by label rather than by value, because {@link TagCell} looks the variant up by the rendered text. */
export const GuardrailExecutionScopeTagVariantMap: Record<
  string,
  TagProps["variant"]
> = {
  [GuardrailExecutionScopeLabelMap[GUARDRAIL_EXECUTION_SCOPE.GLOBAL]]: "green",
  [GuardrailExecutionScopeLabelMap[GUARDRAIL_EXECUTION_SCOPE.ON_REQUEST]]:
    "gray",
};

/** The PII classes a policy can detect: the ones the guardrails check supports. */
export const POLICY_PII_ENTITIES: PiiSupportedEntities[] = [
  PiiSupportedEntities.CREDIT_CARD,
  PiiSupportedEntities.PHONE_NUMBER,
  PiiSupportedEntities.EMAIL_ADDRESS,
  PiiSupportedEntities.IBAN_CODE,
  PiiSupportedEntities.IP_ADDRESS,
  PiiSupportedEntities.NRP,
  PiiSupportedEntities.LOCATION,
  PiiSupportedEntities.PERSON,
  PiiSupportedEntities.CRYPTO,
  PiiSupportedEntities.MEDICAL_LICENSE,
  PiiSupportedEntities.URL,
];

export const PIIEntitiesLabelMap: Partial<
  Record<PiiSupportedEntities, string>
> = {
  [PiiSupportedEntities.CREDIT_CARD]: "Credit card number",
  [PiiSupportedEntities.PHONE_NUMBER]: "Phone number",
  [PiiSupportedEntities.EMAIL_ADDRESS]: "Email",
  [PiiSupportedEntities.IBAN_CODE]: "Bank account number",
  [PiiSupportedEntities.IP_ADDRESS]: "IP address",
  [PiiSupportedEntities.NRP]: "National Registration Plate",
  [PiiSupportedEntities.LOCATION]: "Address",
  [PiiSupportedEntities.PERSON]: "Name",
  [PiiSupportedEntities.CRYPTO]: "Cryptocurrency",
  [PiiSupportedEntities.MEDICAL_LICENSE]: "Medical license",
  [PiiSupportedEntities.URL]: "URL",
};
