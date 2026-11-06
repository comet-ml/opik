import {
  GuardrailComputedResult,
  GuardrailResult,
  GuardrailTypes,
  GuardrailValidation,
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
