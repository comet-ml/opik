import React from "react";
import { UseFormReturn } from "react-hook-form";
import { Label } from "@/components/ui/label";
import { PROVIDER_TYPE } from "@/types/providers";
import { PROVIDERS } from "@/constants/providers";
import EyeInput from "@/components/shared/EyeInput/EyeInput";
import { AIProviderFormType } from "@/components/pages-shared/llm/ManageAIProviderDialog/schema";
import get from "lodash/get";
import {
  FormControl,
  FormField,
  FormItem,
  FormMessage,
} from "@/components/ui/form";
import { cn } from "@/lib/utils";
import { Input } from "@/components/ui/input";

type VllmAIProviderDetailsProps = {
  form: UseFormReturn<AIProviderFormType>;
};

const VllmAIProviderDetails: React.FC<VllmAIProviderDetailsProps> = ({
  form,
}) => {
  const providerName = PROVIDERS[PROVIDER_TYPE.VLLM].label;
  const urlLabel = `${providerName} URL`;
  const apiKeyLabel = `${providerName} API Key`;

  return (
    <div className="flex flex-col gap-2 pb-4">
      <div className="flex flex-col gap-2">
        <FormField
          control={form.control}
          name="url"
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, ["url"]);

            return (
              <FormItem>
                <Label htmlFor="url">{urlLabel}</Label>
                <FormControl>
                  <Input
                    id="url"
                    placeholder={urlLabel}
                    value={field.value}
                    onChange={(e) => field.onChange(e.target.value)}
                    className={cn({
                      "border-destructive": Boolean(validationErrors?.message),
                    })}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            );
          }}
        />
      </div>

      <FormField
        control={form.control}
        name="apiKey"
        render={({ field, formState }) => {
          const validationErrors = get(formState.errors, ["apiKey"]);

          return (
            <FormItem>
              <Label htmlFor="apiKey">{apiKeyLabel}</Label>
              <FormControl>
                <EyeInput
                  id="apiKey"
                  placeholder={apiKeyLabel}
                  value={field.value}
                  onChange={(e) => field.onChange(e.target.value)}
                  className={cn({
                    "border-destructive": Boolean(validationErrors?.message),
                  })}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          );
        }}
      />

      <span className="comet-body-s text-light-slate mt-1">
        You may or may not need an API key for vLLM, depending on your server
        configuration.
      </span>
    </div>
  );
};

export default VllmAIProviderDetails;
