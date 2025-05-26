import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import isObject from "lodash/isObject";

import api, { PROMPTS_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";
import { Prompt } from "@/types/prompts";
import { extractIdFromLocation } from "@/lib/utils";

interface CreatePromptTemplate {
  template: string;
  metadata?: object;
}

type UsePromptCreateMutationParams = {
  prompt: Partial<Prompt> & CreatePromptTemplate;
  withResponse?: boolean;
};

const usePromptCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      prompt,
      withResponse,
    }: UsePromptCreateMutationParams) => {
      const { data, headers } = await api.post(PROMPTS_REST_ENDPOINT, {
        ...prompt,
      });

      const extractedId = extractIdFromLocation(headers?.location);
      if (extractedId && withResponse) {
        const { data: promptData } = await api.get(
          `${PROMPTS_REST_ENDPOINT}${extractedId}`,
        );

        // monkey patch latest_version with prompt_id, to be compatible with PromptVersion object
        if (isObject(promptData.latest_version)) {
          promptData.latest_version.prompt_id = promptData.id;
        }

        return promptData;
      }

      return data
        ? data
        : {
            ...prompt,
            id: extractedId,
          };
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
    onSettled: () => {
      return queryClient.invalidateQueries({ queryKey: ["prompts"] });
    },
  });
};

export default usePromptCreateMutation;
