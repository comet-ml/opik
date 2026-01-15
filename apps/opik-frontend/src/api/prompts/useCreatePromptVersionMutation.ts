import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { PROMPTS_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";
import {
  PromptVersion,
  PROMPT_TEMPLATE_STRUCTURE,
  PROMPT_TYPE,
} from "@/types/prompts";

type UseCreatePromptVersionMutationParams = {
  name: string;
  template: string;
  metadata?: object;
  changeDescription?: string;
  templateStructure?: PROMPT_TEMPLATE_STRUCTURE;
  type?: PROMPT_TYPE;
  onSuccess: (promptVersion: PromptVersion) => void;
};

const useCreatePromptVersionMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      name,
      template,
      metadata,
      changeDescription,
      templateStructure,
      type,
    }: UseCreatePromptVersionMutationParams) => {
      const { data } = await api.post(`${PROMPTS_REST_ENDPOINT}versions`, {
        name,
        version: {
          template,
          ...(metadata && { metadata }),
          ...(changeDescription && { change_description: changeDescription }),
          ...(type && { type }),
        },
        ...(templateStructure && { template_structure: templateStructure }),
      });

      return data;
    },
    onError: (error: AxiosError) => {
      const message = get(
        error,
        ["response", "data", "message"],
        error.message,
      );

      toast({
        title: "Error",
        description: message,
        variant: "destructive",
      });
    },
    onSuccess: async (data: PromptVersion, { onSuccess }) => {
      onSuccess(data);

      // Invalidate prompt-related queries to ensure UI reflects the new version
      // The loadedChatPromptRef in PlaygroundPrompt prevents unwanted re-loading
      queryClient.invalidateQueries({
        queryKey: ["prompt", { promptId: data.prompt_id }],
      });

      // Invalidate the versions list query to show the new version in prompt details page
      // Using predicate to match all versions queries for this specific prompt
      queryClient.invalidateQueries({
        predicate: (query) =>
          query.queryKey[0] === "prompt-versions" &&
          typeof query.queryKey[1] === "object" &&
          query.queryKey[1] !== null &&
          "promptId" in query.queryKey[1] &&
          query.queryKey[1].promptId === data.prompt_id,
      });
    },
  });
};

export default useCreatePromptVersionMutation;
