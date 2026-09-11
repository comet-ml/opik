import { useMutation } from "@tanstack/react-query";
import axios from "axios";
import { APP_VERSION } from "@/constants/app";
import { STATS_ANONYMOUS_ID, STATS_COMET_ENDPOINT } from "@/api/api";
import { useLoggedInUserName } from "@/store/AppStore";

type UseSubmitOnboardingAnswerMutationParams = {
  answer: string;
  step: string;
};

const EVENT_TYPE = "opik_onboarding_answer_fe";

const useSubmitOnboardingAnswerMutation = () => {
  const username = useLoggedInUserName();

  return useMutation({
    mutationFn: async ({
      answer,
      step,
    }: UseSubmitOnboardingAnswerMutationParams) => {
      return axios.post(STATS_COMET_ENDPOINT, {
        anonymous_id: STATS_ANONYMOUS_ID,
        event_type: EVENT_TYPE,
        event_properties: {
          username,
          answer,
          step,
          version: APP_VERSION || null,
        },
      });
    },
  });
};

export default useSubmitOnboardingAnswerMutation;
