import { PROVIDER_TYPE } from "@/types/providers";

export const TOKEN_LIMIT_KEYS = ["max_tokens", "max_completion_tokens"];

// The backend (CustomLlmProvider) sends these providers the token limit under the one name each reads,
// whichever name it was set under.
export const TOKEN_LIMIT_KEY_SENT: Partial<Record<PROVIDER_TYPE, string>> = {
  [PROVIDER_TYPE.OLLAMA]: "max_tokens",
  [PROVIDER_TYPE.BEDROCK]: "max_completion_tokens",
};

type SliderKey =
  | "temperature"
  | "top_p"
  | "max_completion_tokens"
  | "frequency_penalty"
  | "presence_penalty";

const SLIDER_KEYS: SliderKey[] = [
  "temperature",
  "top_p",
  "max_completion_tokens",
  "frequency_penalty",
  "presence_penalty",
];

interface ExtraBodySliderOverlapParams {
  extraBody?: Record<string, unknown> | null;
  sentSliders: Partial<Record<SliderKey, boolean>>;
  provider?: PROVIDER_TYPE;
  exclusiveSampling: boolean;
}

export const getExtraBodySliderOverlap = ({
  extraBody,
  sentSliders,
  provider,
  exclusiveSampling,
}: ExtraBodySliderOverlapParams) => {
  const inExtraBody = (key: string) => extraBody?.[key] !== undefined;

  const tokenLimitKeys = TOKEN_LIMIT_KEYS.filter(inExtraBody);
  const tokenLimitKeySent = provider && TOKEN_LIMIT_KEY_SENT[provider];
  const tokenLimitNames = !tokenLimitKeySent
    ? tokenLimitKeys
    : tokenLimitKeys.includes(tokenLimitKeySent)
      ? [tokenLimitKeySent]
      : tokenLimitKeys.map((key) => `${key} (as ${tokenLimitKeySent})`);

  const sliderKeysInExtraBody = SLIDER_KEYS.filter(
    (key) => sentSliders[key],
  ).flatMap((key) =>
    key === "max_completion_tokens"
      ? tokenLimitNames
      : inExtraBody(key)
        ? [key]
        : [],
  );

  const [sliderKeyNextToExtraBody, extraBodyKeyNextToSlider]: Array<
    string | undefined
  > = !exclusiveSampling
    ? []
    : sentSliders.temperature && inExtraBody("top_p")
      ? ["temperature", "top_p"]
      : sentSliders.top_p && inExtraBody("temperature")
        ? ["top_p", "temperature"]
        : [];

  return {
    sliderKeysInExtraBody,
    sliderKeyNextToExtraBody,
    extraBodyKeyNextToSlider,
  };
};
