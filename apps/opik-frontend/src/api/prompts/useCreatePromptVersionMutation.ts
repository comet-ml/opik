import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { PROMPTS_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";

// type UseCreatePromptVersionMutationParams = {
//   name: string;
//   id: string;
//   template: string;
// };

const useCreatePromptVersionMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async () => {
      const { data } = await api.post(`${PROMPTS_REST_ENDPOINT}/versions`, {
        ...prompt,
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
    onSettled: () => {
      return queryClient.invalidateQueries({ queryKey: ["prompt-versions"] });
    },
  });
};

export default useCreatePromptVersionMutation;
