import React, { useEffect, useMemo, useRef, useState } from "react";
import { UseFormReturn, useWatch } from "react-hook-form";
import { ExternalLink } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Description } from "@/components/ui/description";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import { Alert } from "@/types/alerts";
import { AlertFormType } from "./schema";
import { TRIGGER_CONFIG } from "@/components/pages/AlertsPage/AddEditAlertPage/helpers";
import WebhookPayloadExample from "./WebhookPayloadExample";
import useWebhookTestMutation from "@/api/alerts/useWebhookTestMutation";
import { useToast } from "@/components/ui/use-toast";
import { z } from "zod";
import { buildDocsUrl } from "@/lib/utils";
import { ALERT_TYPE } from "@/types/alerts";

type TestWebhookSectionProps = {
  form: UseFormReturn<AlertFormType>;
  getAlert: () => Partial<Alert>;
  isPending: boolean;
};

const urlSchema = z
  .string({ required_error: "Endpoint URL is required" })
  .min(1, { message: "Endpoint URL is required" })
  .url({ message: "Please enter a valid URL" });

const routingKeySchema = z
  .string()
  .min(1, { message: "Routing key is required for PagerDuty integration" });

const TestWebhookSection: React.FunctionComponent<TestWebhookSectionProps> = ({
  form,
  getAlert,
  isPending,
}) => {
  const { toast } = useToast();
  const { mutate, isPending: isTestPending } = useWebhookTestMutation();
  const [expandedItem, setExpandedItem] = useState<string>("");
  const hadTriggersRef = useRef(false);

  const triggers = useWatch({
    control: form.control,
    name: "triggers",
  });

  const alertType = useWatch({
    control: form.control,
    name: "alertType",
  });

  const triggerItems = useMemo(() => {
    if (!triggers?.length) return [];

    return triggers.map((trigger) => ({
      eventType: trigger.eventType,
      label: TRIGGER_CONFIG[trigger.eventType]?.title || trigger.eventType,
    }));
  }, [triggers]);

  useEffect(() => {
    const hasTriggers = triggerItems.length > 0;
    const hadTriggers = hadTriggersRef.current;

    if (!hasTriggers) {
      setExpandedItem("");
      hadTriggersRef.current = false;
      return;
    }

    const isFirstTriggerAdded = !hadTriggers && hasTriggers;

    setExpandedItem((currentExpandedItem) => {
      if (isFirstTriggerAdded) {
        return triggerItems[0].eventType;
      }

      const isCurrentItemStillValid = triggerItems.some(
        (item) => item.eventType === currentExpandedItem,
      );
      return isCurrentItemStillValid ? currentExpandedItem : "";
    });

    hadTriggersRef.current = true;
  }, [triggerItems]);

  const validateAndTest = (payload: Partial<Alert>, successMessage: string) => {
    const url = payload.webhook?.url || "";

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

    if (payload.alert_type === ALERT_TYPE.pagerduty) {
      const routingKey = payload.metadata?.routing_key || "";
      const routingKeyValidation = routingKeySchema.safeParse(routingKey);
      if (!routingKeyValidation.success) {
        const errorMessage =
          routingKeyValidation.error.errors[0]?.message ||
          "Routing key is required for PagerDuty integration";
        toast({
          description: errorMessage,
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
  };

  const handleTestConnection = () => {
    const alert = getAlert();
    const connectionPayload: Partial<Alert> = {
      ...alert,
      triggers: [],
    };

    validateAndTest(connectionPayload, "Webhook connection test successful!");
  };

  const handleTestTrigger = (eventType: string, label: string) => {
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

    const triggerPayload: Partial<Alert> = {
      ...alert,
      triggers: [triggerToTest],
    };

    validateAndTest(triggerPayload, `Webhook test successful for "${label}"!`);
  };

  return (
    <div className="flex flex-col gap-4">
      <div className="mt-2 flex flex-col gap-1">
        <h3 className="comet-body-accented">Test alert configuration</h3>
        <Description>
          Send a test notification to your configured endpoint to verify that
          the webhook is set up correctly and receiving events as expected.
        </Description>
      </div>

      <div className="flex flex-col gap-3">
        <div className="flex items-center justify-between">
          <Button
            type="button"
            variant="outline"
            onClick={handleTestConnection}
            disabled={isPending || isTestPending}
          >
            {isTestPending && (
              <div className="mr-2 size-4 animate-spin rounded-full border-2 border-light-slate border-r-transparent" />
            )}
            Test connection
          </Button>
          <Button variant="ghost" asChild>
            <a
              href={buildDocsUrl("/production/alerts")}
              target="_blank"
              rel="noreferrer"
            >
              Go to docs
              <ExternalLink className="m-2 size-3.5 shrink-0" />
            </a>
          </Button>
        </div>
      </div>

      {triggerItems.length > 0 && (
        <Accordion
          type="single"
          collapsible
          value={expandedItem}
          onValueChange={setExpandedItem}
          className="border-t border-border"
        >
          {triggerItems.map((item) => (
            <AccordionItem key={item.eventType} value={item.eventType}>
              <AccordionTrigger className="hover:no-underline">
                {item.label}
              </AccordionTrigger>
              <AccordionContent className="px-3">
                <WebhookPayloadExample
                  eventType={item.eventType}
                  alertType={alertType}
                  alert={getAlert()}
                  actionButton={
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      onClick={() =>
                        handleTestTrigger(item.eventType, item.label)
                      }
                      disabled={isPending || isTestPending}
                      className="px-0"
                    >
                      {isTestPending && (
                        <div className="mr-2 size-4 animate-spin rounded-full border-2 border-light-slate border-r-transparent" />
                      )}
                      Test trigger
                    </Button>
                  }
                />
              </AccordionContent>
            </AccordionItem>
          ))}
        </Accordion>
      )}
    </div>
  );
};

export default TestWebhookSection;
