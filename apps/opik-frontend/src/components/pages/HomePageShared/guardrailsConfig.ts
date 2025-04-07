export const GuardrailTypes = {
  TOPIC: "TOPIC",
  PII: "PII",
} as const;
export type GuardrailType = keyof typeof GuardrailTypes;

export const PiiSupportedEntities = {
  CREDIT_CARD: "CREDIT_CARD",
  CRYPTO: "CRYPTO",
  EMAIL_ADDRESS: "EMAIL_ADDRESS",
  IBAN_CODE: "IBAN_CODE",
  IP_ADDRESS: "IP_ADDRESS",
  NRP: "NRP",
  LOCATION: "LOCATION",
  PERSON: "PERSON",
  PHONE_NUMBER: "PHONE_NUMBER",
  MEDICAL_LICENSE: "MEDICAL_LICENSE",
  URL: "URL",
} as const;
export type PiiSupportedEntity = keyof typeof PiiSupportedEntities;

export interface GuardrailConfig {
  id: string;
  title: string;
  hintText: string;
  enabled: boolean;
  threshold: number;
  entities: string[];
  codeImportName: string;
  codeBuilder: (entities: string[], threshold: number) => string;
}

export const guardrailsMap: Record<GuardrailType, GuardrailConfig> = {
  [GuardrailTypes.TOPIC]: {
    id: "topic-guardrail",
    title: "Topic guardrail",
    hintText:
      "The topic guardrail is designed to prevent the model from generating responses on certain topics that might be inappropriate, unsafe, unethical, or outside its intended scope.",
    enabled: true,
    threshold: 0.8,
    entities: [],
    codeImportName: "Topic",
    codeBuilder(entities, threshold) {
      return `Topic(restricted_topics=${JSON.stringify(
        entities,
      )}, threshold=${threshold})`;
    },
  },
  [GuardrailTypes.PII]: {
    id: "pii-guardrail",
    title: "PII guardrail",
    hintText:
      "The PII (Personally Identifiable Information) guardrail is designed to prevent the model from generating, storing, or processing sensitive personal data that could identify individuals.",
    enabled: true,
    threshold: 0.5,
    entities: [
      PiiSupportedEntities.CREDIT_CARD,
      PiiSupportedEntities.PHONE_NUMBER,
    ],
    codeImportName: "PII",
    codeBuilder(entities, threshold) {
      return `PII(blocked_entities=${JSON.stringify(
        entities,
      )}, threshold=${threshold})`;
    },
  },
};

export type GuardrailsState = Record<
  GuardrailType,
  Pick<GuardrailConfig, "threshold" | "entities" | "enabled">
>;
export const guardrailsDefaultState: GuardrailsState = (
  Object.keys(guardrailsMap) as GuardrailType[]
).reduce<GuardrailsState>((acc, key) => {
  acc[key] = {
    threshold: guardrailsMap[key].threshold,
    entities: guardrailsMap[key].entities,
    enabled: guardrailsMap[key].enabled,
  };
  return acc;
}, {} as GuardrailsState);
