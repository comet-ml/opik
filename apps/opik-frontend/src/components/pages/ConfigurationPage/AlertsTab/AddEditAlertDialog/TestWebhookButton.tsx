import React, { useCallback } from "react";
import { z } from "zod";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/use-toast";

import { Alert, WebhookTestResult } from "@/types/alerts";
import useWebhookTestMutation from "@/api/alerts/useWebhookTestMutation";

type TestWebhookButtonProps = {
  getAlert: () => Partial<Alert>;
  disabled?: boolean;
};

// Use the same validation as in the form schema
const urlSchema = z
  .string({ required_error: "Endpoint URL is required" })
  .min(1, { message: "Endpoint URL is required" })
  .url({ message: "Please enter a valid URL" });

const TestWebhookButton: React.FunctionComponent<TestWebhookButtonProps> = ({
  getAlert,
  disabled = false,
}) => {
  const { toast } = useToast();

  const { mutate, isPending } = useWebhookTestMutation();

  const handleTestWebhook = useCallback(() => {
    const alert = getAlert();
    const url = alert.webhook?.url || "";

    // Validate URL using the same schema as the form
    const validation = urlSchema.safeParse(url);
    if (!validation.success) {
      const errorMessage =
        validation.error.errors[0]?.message ||
        "Please enter a valid webhook URL before testing";
      toast({
        description: errorMessage,
        variant: "destructive",
      });
      return;
    }

    mutate(alert as Alert, {
      onSuccess: (result: WebhookTestResult) => {
        if (result.status === "success") {
          toast({
            description: `Webhook test successful! Server responded with status ${result.status_code}`,
          });
        } else {
          toast({
            description: `Webhook test failed: ${
              result.error_message ||
              `Server responded with status ${result.status_code}`
            }`,
            variant: "destructive",
          });
        }
      },
    });
  }, [getAlert, mutate, toast]);

  return (
    <Button
      type="button"
      variant="secondary"
      onClick={handleTestWebhook}
      disabled={disabled || isPending}
    >
      {isPending && (
        <div className="mr-2 size-4 animate-spin rounded-full border-2 border-light-slate border-r-transparent" />
      )}
      Test webhook
    </Button>
  );
};

export default TestWebhookButton;
