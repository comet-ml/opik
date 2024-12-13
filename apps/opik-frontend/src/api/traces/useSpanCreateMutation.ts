import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { SPANS_KEY, SPANS_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";
import { SPAN_TYPE } from "@/types/traces";

import { JsonNode, UsageType } from "@/types/shared";
import { snakeCaseObj } from "@/lib/utils";

type UseSpanCreateMutationParams = {
  id: string;
  projectName?: string;
  traceId: string;
  parentSpanId?: string;
  name: string;
  type: SPAN_TYPE;
  startTime: string;
  endTime?: string;
  input?: JsonNode;
  output?: JsonNode;
  model?: string;
  provider?: string;
  tags?: string[];
  usage?: UsageType;
  metadata?: object;
};

const useSpanCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async (span: UseSpanCreateMutationParams) => {
      const { data } = await api.post(SPANS_REST_ENDPOINT, snakeCaseObj(span));

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
    onSettled: (data, error, variables) => {
      if (variables.projectName) {
        queryClient.invalidateQueries({
          queryKey: ["projects"],
        });
      }

      queryClient.invalidateQueries({
        queryKey: [SPANS_KEY],
      });
    },
  });
};

export default useSpanCreateMutation;
