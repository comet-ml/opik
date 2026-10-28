import { GuardrailTypes, PiiSupportedEntities } from "@/types/guardrails";

export type GuardrailFields = {
  threshold: number;
  entities: string[];
  modelName: string;
  model: string;
  instructions: string;
  name: string;
};

export interface GuardrailConfig extends GuardrailFields {
  id: string;
  title: string;
  hintText: string;
  enabled: boolean;
  codeImportName: string;
  codeBuilder: (fields: GuardrailFields) => string;
}

const EMPTY_FIELDS: GuardrailFields = {
  threshold: 0.5,
  entities: [],
  modelName: "",
  model: "",
  instructions: "",
  name: "",
};

export const guardrailsMap: Record<GuardrailTypes, GuardrailConfig> = {
  [GuardrailTypes.TOPIC]: {
    ...EMPTY_FIELDS,
    id: "topic-guardrail",
    title: "Topic guardrail",
    hintText:
      "The topic guardrail is designed to prevent the model from generating responses on certain topics that might be inappropriate, unsafe, unethical, or outside its intended scope.",
    enabled: true,
    codeImportName: "Topic",
    codeBuilder({ entities }) {
      return `Topic(restricted_topics=${JSON.stringify(entities)})`;
    },
  },
  [GuardrailTypes.PII]: {
    ...EMPTY_FIELDS,
    id: "pii-guardrail",
    title: "PII guardrail",
    hintText:
      "The PII (Personally Identifiable Information) guardrail is designed to prevent the model from generating, storing, or processing sensitive personal data that could identify individuals.",
    enabled: true,
    entities: [
      PiiSupportedEntities.CREDIT_CARD,
      PiiSupportedEntities.PHONE_NUMBER,
    ],
    codeImportName: "PII",
    codeBuilder({ entities }) {
      return `PII(entities=${JSON.stringify(entities)})`;
    },
  },
  [GuardrailTypes.PROMPT_INJECTION]: {
    ...EMPTY_FIELDS,
    id: "prompt-injection-guardrail",
    title: "Prompt injection guardrail",
    hintText:
      "The prompt injection guardrail detects attempts to manipulate the assistant, such as prompt injection and jailbreaks.",
    enabled: false,
    codeImportName: "PromptInjection",
    codeBuilder() {
      return "PromptInjection()";
    },
  },
  [GuardrailTypes.CUSTOM_CLASSIFIER]: {
    ...EMPTY_FIELDS,
    id: "custom-classifier-guardrail",
    title: "Custom guardrail",
    hintText:
      "The custom guardrail runs a binary classifier you trained on your own labeled examples. The guardrails server loads the model by name from its local adapters directory.",
    enabled: false,
    threshold: 0.5,
    codeImportName: "CustomGuardrail",
    codeBuilder({ modelName, threshold }) {
      return `CustomGuardrail(model_name=${JSON.stringify(
        modelName,
      )}, threshold=${threshold})`;
    },
  },
  [GuardrailTypes.LLM_JUDGE]: {
    ...EMPTY_FIELDS,
    id: "llm-judge-guardrail",
    title: "LLM judge guardrail",
    hintText:
      "The LLM judge guardrail validates text against a natural-language policy using an LLM. It runs in the SDK against the provider configured in your Opik workspace and does not require the guardrails server.",
    enabled: false,
    codeImportName: "LLMJudge",
    codeBuilder({ name, instructions, model }) {
      return `LLMJudge(name=${JSON.stringify(
        name,
      )}, instructions=${JSON.stringify(instructions)}, model=${JSON.stringify(
        model,
      )})`;
    },
  },
};

export type GuardrailsState = Record<
  GuardrailTypes,
  GuardrailFields & { enabled: boolean }
>;

export const guardrailsDefaultState: GuardrailsState = (
  Object.keys(guardrailsMap) as GuardrailTypes[]
).reduce<GuardrailsState>((acc, key) => {
  const { threshold, entities, modelName, model, instructions, name, enabled } =
    guardrailsMap[key];
  acc[key] = {
    threshold,
    entities,
    modelName,
    model,
    instructions,
    name,
    enabled,
  };
  return acc;
}, {} as GuardrailsState);

// Topic and PII with nothing to check would raise a ValueError in the SDK, so they stay out of the
// code until the user enters something.
const hasRequiredInput: Partial<
  Record<GuardrailTypes, (fields: GuardrailFields) => boolean>
> = {
  [GuardrailTypes.TOPIC]: ({ entities }) => entities.length > 0,
  [GuardrailTypes.PII]: ({ entities }) => entities.length > 0,
};

export const buildGuardrailCode = (
  state: GuardrailsState,
  enabledTypes: GuardrailTypes[],
) => {
  const rules = enabledTypes
    .map((type) => ({
      type,
      fields: {
        ...state[type],
        entities: state[type].entities
          .map((value) => value.trim())
          .filter(Boolean),
      },
    }))
    .filter(({ type, fields }) => hasRequiredInput[type]?.(fields) ?? true);

  return {
    importNames: rules.map(({ type }) => guardrailsMap[type].codeImportName),
    codeList: rules.map(({ type, fields }) =>
      guardrailsMap[type].codeBuilder(fields),
    ),
  };
};
