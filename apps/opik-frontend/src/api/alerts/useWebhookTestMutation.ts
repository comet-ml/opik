import { useMutation } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import api, { ALERTS_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";
import { Alert, WebhookTestResult } from "@/types/alerts";

const testWebhook = async (
  alert: Partial<Alert>,
): Promise<WebhookTestResult> => {
  const { data } = await api.post<WebhookTestResult>(
    `${ALERTS_REST_ENDPOINT}webhooks/tests`,
    alert,
  );
  return data;
};

export default function useWebhookTestMutation() {
  const { toast } = useToast();

  return useMutation({
    mutationFn: testWebhook,
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
}
