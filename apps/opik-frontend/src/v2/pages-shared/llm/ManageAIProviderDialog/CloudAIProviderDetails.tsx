import React from "react";
import { UseFormReturn } from "react-hook-form";
import { Button } from "@/ui/button";
import { Label } from "@/ui/label";
import { PROVIDER_TYPE } from "@/types/providers";
import { PROVIDER_OPTION_TYPE, PROVIDERS } from "@/constants/providers";
import EyeInput from "@/shared/EyeInput/EyeInput";
import {
  AIProviderFormType,
  DEFAULT_OPENAI_PIPELINE_MODE,
  OpenAiPipelineMode,
} from "@/v2/pages-shared/llm/ManageAIProviderDialog/schema";
import get from "lodash/get";
import { FormControl, FormField, FormItem, FormMessage } from "@/ui/form";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/ui/select";
import { cn } from "@/lib/utils";

type CloudAIProviderDetailsProps = {
  provider: PROVIDER_TYPE | "";
  form: UseFormReturn<AIProviderFormType>;
};

const PIPELINE_MODE_OPTIONS: { value: OpenAiPipelineMode; label: string }[] = [
  { value: "chat_completions_api", label: "Chat Completions API" },
  { value: "responses_api", label: "Responses API" },
];

const CloudAIProviderDetails: React.FC<CloudAIProviderDetailsProps> = ({
  provider,
  form,
}) => {
  const providerName = (provider && PROVIDERS[provider]?.label + " ") || "";
  const apiKeyLabel = `${providerName}API Key`;
  const isOpenAi = provider === PROVIDER_TYPE.OPEN_AI;

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
              href={(PROVIDERS[provider] as PROVIDER_OPTION_TYPE)?.apiKeyURL}
              target="_blank"
              rel="noreferrer"
            >
              here
            </a>
          </Button>
          .
        </span>
      )}
      {isOpenAi && (
        <FormField
          control={form.control}
          name="openaiPipelineMode"
          render={({ field }) => (
            <FormItem className="mt-2">
              <Label htmlFor="openaiPipelineMode">Pipeline mode</Label>
              <Select
                value={field.value ?? DEFAULT_OPENAI_PIPELINE_MODE}
                onValueChange={(value: OpenAiPipelineMode) =>
                  field.onChange(value)
                }
              >
                <SelectTrigger id="openaiPipelineMode">
                  <SelectValue placeholder="Select pipeline mode" />
                </SelectTrigger>
                <SelectContent>
                  {PIPELINE_MODE_OPTIONS.map((opt) => (
                    <SelectItem key={opt.value} value={opt.value}>
                      {opt.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <span className="comet-body-s mt-1 text-light-slate">
                Chat Completions API is the standard endpoint. Responses API
                enables agentic tooling features (e.g. stateful tool loops,
                reasoning summaries) on models that support it.
              </span>
              <FormMessage />
            </FormItem>
          )}
        />
      )}
    </div>
  );
};

export default CloudAIProviderDetails;
