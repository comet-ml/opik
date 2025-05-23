import React from "react";
import { UseFormReturn } from "react-hook-form";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { PROVIDER_TYPE } from "@/types/providers";
import { CLOUD_PROVIDER_OPTION_TYPE, PROVIDERS } from "@/constants/providers";
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

type CloudAIProviderDetailsProps = {
  provider: PROVIDER_TYPE | "";
  form: UseFormReturn<AIProviderFormType>;
};

const CloudAIProviderDetails: React.FC<CloudAIProviderDetailsProps> = ({
  provider,
  form,
}) => {
  const providerName = (provider && PROVIDERS[provider]?.label + " ") || "";
  const apiKeyLabel = `${providerName}API Key`;

  return (
    <div className="flex flex-col gap-2 pb-4">
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
      {provider && (
        <span className="comet-body-s mt-1 text-light-slate">
          Get your {providerName} API key{" "}
          <Button variant="link" size="sm" asChild className="px-0">
            <a
              href={
                (PROVIDERS[provider] as CLOUD_PROVIDER_OPTION_TYPE)?.apiKeyURL
              }
              target="_blank"
              rel="noreferrer"
            >
              here
            </a>
          </Button>
          .
        </span>
      )}
    </div>
  );
};

export default CloudAIProviderDetails;
