import React from "react";
import { UseFormReturn } from "react-hook-form";
import get from "lodash/get";

import { cn } from "@/lib/utils";
import { Label } from "@/ui/label";
import { FormControl, FormField, FormItem, FormMessage } from "@/ui/form";
import { Input } from "@/ui/input";
import { Button } from "@/ui/button";
import EyeInput from "@/shared/EyeInput/EyeInput";
import { Description } from "@/ui/description";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/ui/accordion";
import { ALERT_TYPE } from "@/types/alerts";
import DestinationSelector from "./DestinationSelector";
import WebhookHeaders from "./WebhookHeaders";
import { AlertFormType } from "./schema";

type WebhookSettingsProps = {
  form: UseFormReturn<AlertFormType>;
  onTestConnection: () => void;
  isTestPending: boolean;
  isPending: boolean;
};

const WebhookSettings: React.FC<WebhookSettingsProps> = ({
  form,
  onTestConnection,
  isTestPending,
  isPending,
}) => {
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1">
        <h3 className="comet-body-accented">Webhook settings</h3>
        <Description>
          Configure how the platform sends notifications to your system.
        </Description>
      </div>

      <FormField
        control={form.control}
        name="alertType"
        render={({ field }) => (
          <FormItem>
            <Label>Destination</Label>
            <FormControl>
              <DestinationSelector
                value={field.value}
                onChange={field.onChange}
              />
            </FormControl>
            <FormMessage />
          </FormItem>
        )}
      />

      {form.watch("alertType") === ALERT_TYPE.pagerduty && (
        <FormField
          control={form.control}
          name="routingKey"
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, ["routingKey"]);
            return (
              <FormItem>
                <Label>Routing Key</Label>
                <Description>
                  PagerDuty routing key for an integration on a service or on a
                  global ruleset
                </Description>
                <FormControl>
                  <Input
                    className={cn({
                      "border-destructive": Boolean(validationErrors?.message),
                    })}
                    placeholder="Enter routing key"
                    {...field}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            );
          }}
        />
      )}

      <FormField
        control={form.control}
        name="url"
        render={({ field, formState }) => {
          const validationErrors = get(formState.errors, ["url"]);
          return (
            <FormItem>
              <div className="flex items-center justify-between">
                <Label>Endpoint URL</Label>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={onTestConnection}
                  disabled={isPending || isTestPending}
                >
                  {isTestPending && (
                    <div className="mr-2 size-4 animate-spin rounded-full border-2 border-light-slate border-r-transparent" />
                  )}
                  Test connection
                </Button>
              </div>
              <FormControl>
                <Input
                  className={cn({
                    "border-destructive": Boolean(validationErrors?.message),
                  })}
                  data-testid="alert-webhook-url-input"
                  placeholder="https://hooks.slack.com/services/..."
                  {...field}
                />
              </FormControl>
              <FormMessage />
              <Description>
                Destination URL where the platform will send webhook
                notifications. This endpoint should be active and able to
                receive and process incoming POST requests.
              </Description>
            </FormItem>
          );
        }}
      />

      <Accordion type="single" collapsible defaultValue="">
        <AccordionItem value="advanced" className="border-t">
          <AccordionTrigger>Advanced webhook settings</AccordionTrigger>
          <AccordionContent>
            <div className="flex flex-col gap-4 px-3">
              <FormField
                control={form.control}
                name="secretToken"
                render={({ field, formState }) => {
                  const validationErrors = get(formState.errors, [
                    "secretToken",
                  ]);
                  return (
                    <FormItem>
                      <Label>Secret token (optional)</Label>
                      <FormControl>
                        <EyeInput
                          className={cn({
                            "border-destructive": Boolean(
                              validationErrors?.message,
                            ),
                          })}
                          placeholder=""
                          {...field}
                        />
                      </FormControl>
                      <FormMessage />
                      <Description>
                        Add to securely verify that incoming webhook requests
                        come from the platform.
                      </Description>
                    </FormItem>
                  );
                }}
              />

              <WebhookHeaders form={form} />
            </div>
          </AccordionContent>
        </AccordionItem>
      </Accordion>
    </div>
  );
};

export default WebhookSettings;
