import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { PROMPTS_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";
import { PromptVersion } from "@/types/prompts";

type UseCreatePromptVersionMutationParams = {
  name: string;
  template: string;
  metadata?: object;
  changeDescription?: string;
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
    }: UseCreatePromptVersionMutationParams) => {
      const { data } = await api.post(`${PROMPTS_REST_ENDPOINT}versions`, {
        name,
        version: {
          template,
          ...(metadata && { metadata }),
          ...(changeDescription && { change_description: changeDescription }),
        },
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
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ["prompt-versions"] });
      queryClient.invalidateQueries({ queryKey: ["prompt"] });
    },
  });
};

export default useCreatePromptVersionMutation;
