import React, { useCallback, useMemo } from "react";
import isEmpty from "lodash/isEmpty";
import omit from "lodash/omit";

import { PROVIDER_TYPE } from "@/types/providers";
import ExtraBodyParametersInput from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/ExtraBodyParametersInput";

// Opik writes the thinking level under custom_parameters.thinking, so the box leaves it to the level control.
const THINKING_KEY = "thinking";

const AI_STUDIO_KEYS =
  "temperature, topP, topK, seed, frequencyPenalty, presencePenalty, maxOutputTokens, stopSequences, responseLogprobs, logprobs, mediaResolution and safetySettings";

const TOOLTIPS: Record<PROVIDER_TYPE.GEMINI | PROVIDER_TYPE.VERTEX_AI, string> =
  {
    [PROVIDER_TYPE.GEMINI]: `Settings from Google's GenerateContentConfig, sent with each request. Google AI Studio takes ${AI_STUDIO_KEYS}. A key here replaces the same setting above.`,
    [PROVIDER_TYPE.VERTEX_AI]:
      "Settings from Google's GenerateContentConfig, sent with each request, such as topK, responseMimeType or safetySettings. A key here replaces the same setting above.",
  };

interface GeminiExtraBodyParametersProps {
  provider: PROVIDER_TYPE.GEMINI | PROVIDER_TYPE.VERTEX_AI;
  customParameters: Record<string, unknown> | null | undefined;
  onChange: (configs: {
    custom_parameters: Record<string, unknown> | null;
  }) => void;
}

const GeminiExtraBodyParameters = ({
  provider,
  customParameters,
  onChange,
}: GeminiExtraBodyParametersProps) => {
  const thinking = customParameters?.[THINKING_KEY];
  // Memoized because the editor resets its text whenever the value it is given changes identity.
  const extraBody = useMemo(() => {
    const keys = omit(customParameters ?? {}, THINKING_KEY);
    return isEmpty(keys) ? null : keys;
  }, [customParameters]);

  const handleChange = useCallback(
    (value: Record<string, unknown> | null) => {
      const next = {
        ...value,
        ...(thinking !== undefined && { [THINKING_KEY]: thinking }),
      };
      onChange({ custom_parameters: isEmpty(next) ? null : next });
    },
    [thinking, onChange],
  );

  return (
    <ExtraBodyParametersInput
      value={extraBody}
      onChange={handleChange}
      tooltip={TOOLTIPS[provider]}
      placeholder='{"topK": 40}'
    />
  );
};

export default GeminiExtraBodyParameters;
