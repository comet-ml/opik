import { useMutation } from "@tanstack/react-query";
import get from "lodash/get";

import axios, { AxiosError } from "axios";

import { useToast } from "@/components/ui/use-toast";
import { APP_VERSION } from "@/constants/app";

type UseProvideFeedbackMutationParams = {
  feedback: string;
  name: string;
  email: string;
};

const EVENT_TYPE = "opik_feedback_fe";
const ANONYMOUS_ID = "guest";

const useProvideFeedbackMutation = () => {
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      feedback,
      name,
      email,
    }: UseProvideFeedbackMutationParams) => {
      // the app's axios instance is not used here
      // as we want to avoid having credentials and a workspace in headers
      return axios.post("https://stats.comet.com/notify/event/", {
        anonymous_id: ANONYMOUS_ID,
        event_type: EVENT_TYPE,
        event_properties: {
          feedback,
          name,
          email,
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

export default useProvideFeedbackMutation;
