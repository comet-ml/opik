import { useMutation, useQueryClient } from "@tanstack/react-query";
import api, { OPEN_SOURCE_WELCOME_WIZARD_REST_ENDPOINT } from "@/api/api";
import {
  OpenSourceWelcomeWizardSubmission,
  OpenSourceWelcomeWizardTracking,
} from "@/types/open-source-welcome-wizard";
import { OPEN_SOURCE_WELCOME_WIZARD_QUERY_KEY } from "./useOpenSourceWelcomeWizardStatus";
import { useToast } from "@/components/ui/use-toast";

const submitOpenSourceWelcomeWizard = async (
  submission: OpenSourceWelcomeWizardSubmission,
) => {
  const { data } = await api.post<OpenSourceWelcomeWizardTracking>(
    OPEN_SOURCE_WELCOME_WIZARD_REST_ENDPOINT,
    submission,
  );
  return data;
};

export default function useOpenSourceWelcomeWizardSubmitMutation() {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: submitOpenSourceWelcomeWizard,
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: [OPEN_SOURCE_WELCOME_WIZARD_QUERY_KEY],
      });
      // Don't show toast here - let the component decide
    },
    onError: (error) => {
      toast({
        description: "Failed to submit welcome wizard.",
        variant: "destructive",
      });
      console.error("Failed to submit welcome wizard:", error);
    },
  });
}
