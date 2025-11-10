import { useCallback } from "react";
import useCompletionProxyStreaming from "@/api/playground/useCompletionProxyStreaming";
import {
  PROMPT_IMPROVEMENT_SYSTEM_PROMPT,
  PROMPT_GENERATION_SYSTEM_PROMPT,
} from "@/constants/promptImprovement";
import { LLMPromptConfigsType, PROVIDER_MODEL_TYPE } from "@/types/providers";
import { LLM_MESSAGE_ROLE, ProviderMessageType } from "@/types/llm";

interface UsePromptImprovementParams {
  workspaceName: string;
}

const usePromptImprovement = ({
  workspaceName,
}: UsePromptImprovementParams) => {
  const runStreaming = useCompletionProxyStreaming({ workspaceName });

  const improvePrompt = useCallback(
    async (
      originalPrompt: string,
      userInstructions: string,
      model: string,
      configs: LLMPromptConfigsType,
      onChunk: (chunk: string) => void,
      signal?: AbortSignal,
    ) => {
      const messages: ProviderMessageType[] = [
        {
          role: LLM_MESSAGE_ROLE.system,
          content: PROMPT_IMPROVEMENT_SYSTEM_PROMPT,
        },
        {
          role: LLM_MESSAGE_ROLE.user,
          content: `Improve this prompt: ${originalPrompt}\n\nUser instructions: ${userInstructions}`,
        },
      ];

      return runStreaming({
        model: model as PROVIDER_MODEL_TYPE | "",
        messages,
        configs,
        onAddChunk: onChunk,
        signal: signal || new AbortController().signal,
      });
    },
    [runStreaming],
  );

  const generatePrompt = useCallback(
    async (
      userInstructions: string,
      model: string,
      configs: LLMPromptConfigsType,
      onChunk: (chunk: string) => void,
      signal?: AbortSignal,
    ) => {
      const messages: ProviderMessageType[] = [
        {
          role: LLM_MESSAGE_ROLE.system,
          content: PROMPT_GENERATION_SYSTEM_PROMPT,
        },
        {
          role: LLM_MESSAGE_ROLE.user,
          content: userInstructions,
        },
      ];

      return runStreaming({
        model: model as PROVIDER_MODEL_TYPE | "",
        messages,
        configs,
        onAddChunk: onChunk,
        signal: signal || new AbortController().signal,
      });
    },
    [runStreaming],
  );

  return { improvePrompt, generatePrompt };
};

export default usePromptImprovement;
