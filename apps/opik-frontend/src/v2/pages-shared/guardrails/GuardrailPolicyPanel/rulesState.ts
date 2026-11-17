import {
  GuardrailPolicyRule,
  GuardrailTypes,
  PiiSupportedEntities,
  TopicRuleConfig,
} from "@/types/guardrails";
import { GUARDRAIL_RULE_TYPES_ORDER } from "@/constants/guardrails";

export const MAX_TOPIC_LENGTH = 256;
/** The backend's limit: a list plus its "none of these" option fills one choice question. */
export const MAX_TOPICS = 254;

type PiiRuleState = {
  enabled: boolean;
  entities: string[];
};

type TopicRuleState = {
  enabled: boolean;
  restrictedTopics: string[];
  allowedTopics: string[];
};

type PromptInjectionRuleState = {
  enabled: boolean;
};

export type RulesState = {
  [GuardrailTypes.PII]: PiiRuleState;
  [GuardrailTypes.TOPIC]: TopicRuleState;
  [GuardrailTypes.PROMPT_INJECTION]: PromptInjectionRuleState;
};

export const DEFAULT_RULES_STATE: RulesState = {
  [GuardrailTypes.PII]: {
    enabled: false,
    entities: [
      PiiSupportedEntities.CREDIT_CARD,
      PiiSupportedEntities.PHONE_NUMBER,
    ],
  },
  [GuardrailTypes.TOPIC]: {
    enabled: false,
    restrictedTopics: [],
    allowedTopics: [],
  },
  [GuardrailTypes.PROMPT_INJECTION]: {
    enabled: false,
  },
};

const trimList = (list: string[]) =>
  list.map((item) => item.trim()).filter(Boolean);

/**
 * Turns a stored policy back into form state. A rule the policy does not contain stays at its
 * default with the toggle off, so turning it on offers sensible values rather than blanks.
 */
export const toRulesState = (rules: GuardrailPolicyRule[]): RulesState => {
  const state: RulesState = {
    [GuardrailTypes.PII]: { ...DEFAULT_RULES_STATE[GuardrailTypes.PII] },
    [GuardrailTypes.TOPIC]: { ...DEFAULT_RULES_STATE[GuardrailTypes.TOPIC] },
    [GuardrailTypes.PROMPT_INJECTION]: {
      ...DEFAULT_RULES_STATE[GuardrailTypes.PROMPT_INJECTION],
    },
  };

  rules.forEach((rule) => {
    switch (rule.type) {
      case GuardrailTypes.PII:
        state[GuardrailTypes.PII] = {
          enabled: true,
          entities: rule.config.entities ?? [],
        };
        break;
      case GuardrailTypes.TOPIC:
        state[GuardrailTypes.TOPIC] = {
          enabled: true,
          restrictedTopics: rule.config.restricted_topics ?? [],
          allowedTopics: rule.config.allowed_topics ?? [],
        };
        break;
      case GuardrailTypes.PROMPT_INJECTION:
        state[GuardrailTypes.PROMPT_INJECTION] = { enabled: true };
        break;
    }
  });

  return state;
};

export const toRulesPayload = (state: RulesState): GuardrailPolicyRule[] =>
  GUARDRAIL_RULE_TYPES_ORDER.reduce<GuardrailPolicyRule[]>((acc, type) => {
    if (!state[type].enabled) {
      return acc;
    }

    switch (type) {
      case GuardrailTypes.PII:
        acc.push({
          type,
          config: { entities: state[type].entities },
        });
        break;
      case GuardrailTypes.TOPIC: {
        const allowed = trimList(state[type].allowedTopics);
        const restricted = trimList(state[type].restrictedTopics);
        const config: TopicRuleConfig = {
          ...(allowed.length > 0 && { allowed_topics: allowed }),
          ...(restricted.length > 0 && { restricted_topics: restricted }),
        };
        acc.push({ type, config });
        break;
      }
      case GuardrailTypes.PROMPT_INJECTION:
        acc.push({ type, config: {} });
        break;
    }

    return acc;
  }, []);

/**
 * Keyed per field rather than per rule, so a message renders against the control it is about.
 */
export type RuleFieldKey = "pii.entities" | "topic.topics";

export type RuleErrors = Partial<Record<RuleFieldKey, string>>;

/** The check adds this option to every topic list itself, so the backend rejects it as a topic. */
const RESERVED_TOPIC = "none of these";

const validateTopicList = (topics: string[]): string | undefined => {
  if (topics.length > MAX_TOPICS) {
    return `A list can have at most ${MAX_TOPICS} topics`;
  }
  if (topics.some((item) => item.length > MAX_TOPIC_LENGTH)) {
    return `A topic can be at most ${MAX_TOPIC_LENGTH} characters`;
  }
  const topicsLowerCase = topics.map((item) => item.toLowerCase());
  if (topicsLowerCase.includes(RESERVED_TOPIC)) {
    return `"${RESERVED_TOPIC}" is reserved: the check adds it to every list itself`;
  }
  // Compared ignoring case, as the backend and the model compare them.
  if (new Set(topicsLowerCase).size !== topics.length) {
    return "Each topic can appear only once in a list";
  }
  return undefined;
};

export const validateRules = (state: RulesState): RuleErrors => {
  const errors: RuleErrors = {};

  const pii = state[GuardrailTypes.PII];
  if (pii.enabled && pii.entities.length === 0) {
    errors["pii.entities"] = "Select at least one category of personal data";
  }

  const topic = state[GuardrailTypes.TOPIC];
  if (topic.enabled) {
    const restricted = trimList(topic.restrictedTopics);
    const allowed = trimList(topic.allowedTopics);
    // Compared ignoring case, as the model reads the topics.
    const restrictedLowerCase = new Set(
      restricted.map((item) => item.toLowerCase()),
    );
    const listError =
      validateTopicList(restricted) ?? validateTopicList(allowed);

    if (restricted.length === 0 && allowed.length === 0) {
      errors["topic.topics"] = "Enter at least one restricted or allowed topic";
    } else if (listError) {
      errors["topic.topics"] = listError;
    } else if (
      allowed.some((item) => restrictedLowerCase.has(item.toLowerCase()))
    ) {
      errors["topic.topics"] = "A topic cannot be both restricted and allowed";
    }
  }

  return errors;
};
