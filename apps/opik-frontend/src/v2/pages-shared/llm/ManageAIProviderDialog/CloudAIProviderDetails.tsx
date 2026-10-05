import React from "react";
import { UseFormReturn } from "react-hook-form";
import { ChevronRight } from "lucide-react";
import { Button } from "@/ui/button";
import { Label } from "@/ui/label";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  CustomAccordionTrigger,
} from "@/ui/accordion";
import { PROVIDER_TYPE } from "@/types/providers";
import { PROVIDER_OPTION_TYPE, PROVIDERS } from "@/constants/providers";
import EyeInput from "@/shared/EyeInput/EyeInput";
import SelectBox from "@/shared/SelectBox/SelectBox";
import {
  AIProviderFormType,
  DEFAULT_OPENAI_PIPELINE_MODE,
  OpenAiPipelineMode,
  supportsProviderHeaders,
} from "@/v2/pages-shared/llm/ManageAIProviderDialog/schema";
import CustomHeadersField from "@/v2/pages-shared/llm/ManageAIProviderDialog/CustomHeadersField";
import { DropdownOption } from "@/types/shared";
import get from "lodash/get";
import { FormControl, FormField, FormItem, FormMessage } from "@/ui/form";
import { cn } from "@/lib/utils";

type CloudAIProviderDetailsProps = {
  provider: PROVIDER_TYPE | "";
  form: UseFormReturn<AIProviderFormType>;
};

const PIPELINE_MODE_OPTIONS: DropdownOption<OpenAiPipelineMode>[] = [
  {
    value: "chat_completions_api",
    label: "Chat Completions API (recommended)",
  },
  { value: "responses_api", label: "Responses API" },
];

const ADVANCED_SETTINGS = "advanced";

const CloudAIProviderDetails: React.FC<CloudAIProviderDetailsProps> = ({
  provider,
  form,
}) => {
  const providerName = (provider && PROVIDERS[provider]?.label + " ") || "";
  const apiKeyLabel = `${providerName}API Key`;
  const isOpenAi = provider === PROVIDER_TYPE.OPEN_AI;
  const hasNonDefaultPipelineMode =
    form.getValues("openaiPipelineMode") !== DEFAULT_OPENAI_PIPELINE_MODE;

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
      {supportsProviderHeaders(provider) && (
        <div className="mt-2">
          <CustomHeadersField
            form={form}
            description={
              provider === PROVIDER_TYPE.OPEN_ROUTER
                ? "Added to every request sent to OpenRouter, e.g. HTTP-Referer and X-OpenRouter-Title for app attribution."
                : `Added to every request sent to ${providerName.trim()} as key-value pairs.`
            }
          />
        </div>
      )}
      {isOpenAi && (
        <Accordion
          type="single"
          collapsible
          className="mt-2"
          defaultValue={hasNonDefaultPipelineMode ? ADVANCED_SETTINGS : ""}
        >
          <AccordionItem value={ADVANCED_SETTINGS} className="border-b-0">
            <CustomAccordionTrigger className="flex items-center gap-1 transition-all [&[data-state=open]>svg]:rotate-90">
              <span className="comet-body-xs">Advanced settings</span>
              <ChevronRight className="size-3.5 shrink-0 transition-transform duration-200" />
            </CustomAccordionTrigger>
            <AccordionContent className="pb-0 pt-2">
              <FormField
                control={form.control}
                name="openaiPipelineMode"
                render={({ field }) => (
                  <FormItem>
                    <Label htmlFor="openaiPipelineMode">OpenAI API</Label>
                    <FormControl>
                      <SelectBox
                        id="openaiPipelineMode"
                        // Field is always seeded for the OpenAI branch by ManageAIProviderDialog
                        // (defaultValues, resetSelectionState, handleProviderSelect), so non-null here.
                        value={field.value!}
                        onChange={(value: OpenAiPipelineMode) =>
                          field.onChange(value)
                        }
                        options={PIPELINE_MODE_OPTIONS}
                        placeholder="Select an API"
                      />
                    </FormControl>
                    <span className="comet-body-s mt-1 text-light-slate">
                      Use Chat Completions unless you need Max reasoning effort,
                      which only the Responses API offers. The Responses API
                      sends text only (no images or audio) and ignores frequency
                      and presence penalty.
                    </span>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </AccordionContent>
          </AccordionItem>
        </Accordion>
      )}
    </div>
  );
};

export default CloudAIProviderDetails;
