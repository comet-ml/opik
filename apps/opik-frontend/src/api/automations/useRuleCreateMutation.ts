import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, { AUTOMATIONS_KEY, AUTOMATIONS_REST_ENDPOINT } from "@/api/api";
import { EvaluatorsRule } from "@/types/automations";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";

type UseRuleCreateMutationParams = {
  rule: Partial<EvaluatorsRule>;
};

const serializeMessageContent = (content: string | any[]): string => {
  if (typeof content === "string") {
    return content;
  }

  // Convert structured content array to string format
  return content
    .map((item: any) => {
      if (item.type === "text") {
        return item.text;
      } else if (item.type === "image_url") {
        // Wrap image variable with placeholder tags for backend parsing
        return `<<<image>>>${item.image_url.url}<<</image>>>`;
      }
      return "";
    })
    .join("");
};

const useRuleCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ rule }: UseRuleCreateMutationParams) => {
      // Clean schema to remove frontend-only fields like 'unsaved'
      // Serialize message content from structured to string format
      const cleanedRule = {
        ...rule,
        code: rule.code
          ? {
              ...rule.code,
              messages: rule.code.messages?.map((msg: any) => ({
                ...msg,
                content: serializeMessageContent(msg.content),
              })),
              schema: rule.code.schema?.map(({ unsaved, ...rest }: any) => rest),
            }
          : rule.code,
      };

      const { data } = await api.post(
        `${AUTOMATIONS_REST_ENDPOINT}evaluators`,
        cleanedRule,
      );
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
      return queryClient.invalidateQueries({
        queryKey: [AUTOMATIONS_KEY],
      });
    },
  });
};

export default useRuleCreateMutation;
