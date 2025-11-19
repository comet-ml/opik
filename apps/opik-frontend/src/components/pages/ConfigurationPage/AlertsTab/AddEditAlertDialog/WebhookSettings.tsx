import React from "react";
import { UseFormReturn } from "react-hook-form";
import get from "lodash/get";

import { cn } from "@/lib/utils";
import { Label } from "@/components/ui/label";
import {
  FormControl,
  FormField,
  FormItem,
  FormMessage,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import EyeInput from "@/components/shared/EyeInput/EyeInput";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { Description } from "@/components/ui/description";
import WebhookHeaders from "./WebhookHeaders";
import { AlertFormType } from "./schema";

type WebhookSettingsProps = {
  form: UseFormReturn<AlertFormType>;
};

const WebhookSettings: React.FC<WebhookSettingsProps> = ({ form }) => {
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1">
        <h3 className="text-sm font-medium">Webhook settings</h3>
        <Description>
          Configure to receive real-time notifications of platform events.
        </Description>
      </div>

      <FormField
        control={form.control}
        name="url"
        render={({ field, formState }) => {
          const validationErrors = get(formState.errors, ["url"]);
          return (
            <FormItem>
              <Label className="flex items-center gap-1">
                Endpoint URL
                <ExplainerIcon description="The webhook URL where notifications will be sent" />
              </Label>
              <FormControl>
                <Input
                  className={cn({
                    "border-destructive": Boolean(validationErrors?.message),
                  })}
                  placeholder="https://hooks.slack.com/services/..."
                  {...field}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          );
        }}
      />

      <FormField
        control={form.control}
        name="secretToken"
        render={({ field, formState }) => {
          const validationErrors = get(formState.errors, ["secretToken"]);
          return (
            <FormItem>
              <Label className="flex items-center gap-1">
                Secret token
                <ExplainerIcon description="Optional token for webhook authentication" />
              </Label>
              <FormControl>
                <EyeInput
                  className={cn({
                    "border-destructive": Boolean(validationErrors?.message),
                  })}
                  placeholder="Enter secret token"
                  {...field}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          );
        }}
      />

      <WebhookHeaders form={form} />
    </div>
  );
};

export default WebhookSettings;
