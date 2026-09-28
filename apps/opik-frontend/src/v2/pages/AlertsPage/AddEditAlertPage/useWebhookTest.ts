import { useCallback } from "react";
import { z } from "zod";

import { Alert, ALERT_TYPE } from "@/types/alerts";
import useWebhookTestMutation from "@/api/alerts/useWebhookTestMutation";
import { useToast } from "@/ui/use-toast";

const urlSchema = z
  .string({ required_error: "Endpoint URL is required" })
  .min(1, { message: "Endpoint URL is required" })
  .url({ message: "Please enter a valid URL" });

const routingKeySchema = z
  .string()
  .min(1, { message: "Routing key is required for PagerDuty integration" });

type UseWebhookTestParams = {
  getAlert: () => Partial<Alert>;
};

// Shared by the "Test connection" button in WebhookSettings and the per-trigger
// "Test trigger" buttons in EventTriggers, so both go through the same
// validation and toast handling and share a single mutation.
const useWebhookTest = ({ getAlert }: UseWebhookTestParams) => {
  const { toast } = useToast();
  const { mutate, isPending: isTestPending } = useWebhookTestMutation();

  const validateAndTest = useCallback(
    (payload: Partial<Alert>, successMessage: string) => {
      const url = payload.webhook?.url || "";

      const validation = urlSchema.safeParse(url);
      if (!validation.success) {
        toast({
          description:
            validation.error.errors[0]?.message ||
            "Please enter a valid webhook URL before testing",
          variant: "destructive",
        });
        return;
      }

      if (payload.alert_type === ALERT_TYPE.pagerduty) {
        const routingKeyValidation = routingKeySchema.safeParse(
          payload.metadata?.routing_key || "",
        );
        if (!routingKeyValidation.success) {
          toast({
            description:
              routingKeyValidation.error.errors[0]?.message ||
              "Routing key is required for PagerDuty integration",
            variant: "destructive",
          });
          return;
        }
      }

      mutate(payload, {
        onSuccess: (data) => {
          if (data.status === "failure") {
            toast({
              title: "Webhook test failed",
              description: data.error_message || "Webhook test failed",
              variant: "destructive",
            });
            return;
          }

          toast({
            description: successMessage,
          });
        },
      });
    },
    [mutate, toast],
  );

  const testConnection = useCallback(() => {
    validateAndTest(
      { ...getAlert(), triggers: [] },
      "Webhook connection test successful!",
    );
  }, [getAlert, validateAndTest]);

  const testTrigger = useCallback(
    (eventType: string, label: string) => {
      const alert = getAlert();
      const triggerToTest = alert.triggers?.find(
        (trigger) => trigger.event_type === eventType,
      );

      if (!triggerToTest) {
        toast({
          description: "Trigger not found",
          variant: "destructive",
        });
        return;
      }

      validateAndTest(
        { ...alert, triggers: [triggerToTest] },
        `Webhook test successful for "${label}"!`,
      );
    },
    [getAlert, toast, validateAndTest],
  );

  return { testConnection, testTrigger, isTestPending };
};

export default useWebhookTest;
