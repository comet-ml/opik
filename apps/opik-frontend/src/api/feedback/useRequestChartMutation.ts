import { useMutation } from "@tanstack/react-query";
import get from "lodash/get";

import axios, { AxiosError } from "axios";

import { useToast } from "@/components/ui/use-toast";
import { APP_VERSION } from "@/constants/app";
import { STATS_ANONYMOUS_ID, STATS_COMET_ENDPOINT } from "@/api/api";

type UseRequestChartMutationParams = {
  feedback: string;
};

const EVENT_TYPE = "opik_request_chart_fe";

const useRequestChartMutation = () => {
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ feedback }: UseRequestChartMutationParams) => {
      return axios.post(STATS_COMET_ENDPOINT, {
        anonymous_id: STATS_ANONYMOUS_ID,
        event_type: EVENT_TYPE,
        event_properties: {
          feedback,
          version: APP_VERSION || null,
        },
      });
    },
    onSuccess: () => {
      toast({
        title: "Feedback sent",
        description: "Thank you for sharing your thoughts with us",
      });
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
  });
};

export default useRequestChartMutation;
