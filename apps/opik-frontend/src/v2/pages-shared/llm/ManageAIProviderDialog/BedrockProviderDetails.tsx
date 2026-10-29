import React from "react";
import { UseFormReturn } from "react-hook-form";
import { Label } from "@/ui/label";
import EyeInput from "@/shared/EyeInput/EyeInput";
import { AIProviderFormType } from "@/v2/pages-shared/llm/ManageAIProviderDialog/schema";
import get from "lodash/get";
import { FormControl, FormField, FormItem, FormMessage } from "@/ui/form";
import { cn } from "@/lib/utils";
import { Input } from "@/ui/input";
import { Description } from "@/ui/description";
import { Button } from "@/ui/button";
import SelectBox from "@/shared/SelectBox/SelectBox";
import { DropdownOption } from "@/types/shared";
import { BedrockApi } from "@/types/providers";
import { normalizeBedrockApi } from "@/lib/provider";
import CustomHeadersField from "./CustomHeadersField";
import AuthConfigSection from "./AuthConfigSection";

const BEDROCK_API_OPTIONS: DropdownOption<BedrockApi>[] = [
  { value: "chat_completions", label: "Chat Completions (OpenAI-compatible)" },
  { value: "anthropic_messages", label: "Anthropic Messages (Claude)" },
  { value: "converse", label: "Converse (Amazon Nova and others)" },
];

const URL_PLACEHOLDERS: Record<BedrockApi, string> = {
  chat_completions: "https://bedrock-runtime.us-east-1.amazonaws.com/openai/v1",
  anthropic_messages:
    "https://bedrock-runtime.us-east-1.amazonaws.com/anthropic",
  converse: "https://bedrock-runtime.us-east-1.amazonaws.com",
};

const MODELS_EXAMPLES: Record<BedrockApi, string> = {
  chat_completions: "openai.gpt-oss-20b-1:0,mistral.ministral-3-3b-instruct",
  anthropic_messages:
    "us.anthropic.claude-haiku-5-5,global.anthropic.claude-sonnet-5",
  converse: "us.amazon.nova-pro-v1:0,us.amazon.nova-2-lite-v1:0",
};

type BedrockProviderDetailsProps = {
  form: UseFormReturn<AIProviderFormType>;
  isEdit?: boolean;
};

const BedrockProviderDetails: React.FC<BedrockProviderDetailsProps> = ({
  form,
  isEdit = false,
}) => {
  const bedrockApi = normalizeBedrockApi(form.watch("bedrockApi"));

  return (
    <div className="flex flex-col gap-4 pb-4">
      {!isEdit && (
        <FormField
          control={form.control}
          name="providerName"
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, ["providerName"]);

            return (
              <FormItem>
                <Label htmlFor="providerName">Provider name</Label>
                <FormControl>
                  <Input
                    id="providerName"
                    placeholder="Bedrock us-east-1"
                    value={field.value}
                    onChange={(e) => field.onChange(e.target.value)}
                    disabled={isEdit}
                    className={cn({
                      "border-destructive": Boolean(validationErrors?.message),
                    })}
                  />
                </FormControl>
                <FormMessage />
                <Description>
                  A unique identifier for this provider instance (e.g.,
                  &quot;Bedrock us-east-1&quot;).
                </Description>
              </FormItem>
            );
          }}
        />
      )}
      <FormField
        control={form.control}
        name="bedrockApi"
        render={({ field }) => (
          <FormItem>
            <Label htmlFor="bedrockApi">API</Label>
            <FormControl>
              <SelectBox
                id="bedrockApi"
                value={normalizeBedrockApi(field.value)}
                onChange={(value: BedrockApi) => field.onChange(value)}
                options={BEDROCK_API_OPTIONS}
              />
            </FormControl>
            <Description>
              Bedrock serves Claude only through Anthropic Messages and Amazon
              Nova only through Converse. Use Chat Completions for
              OpenAI-compatible models such as gpt-oss.
            </Description>
            <FormMessage />
          </FormItem>
        )}
      />
      <FormField
        control={form.control}
        name="url"
        render={({ field, formState }) => {
          const validationErrors = get(formState.errors, ["url"]);

          return (
            <FormItem>
              <Label htmlFor="url">URL</Label>
              <FormControl>
                <Input
                  id="url"
                  placeholder={URL_PLACEHOLDERS[bedrockApi]}
                  value={field.value}
                  onChange={(e) => field.onChange(e.target.value)}
                  className={cn({
                    "border-destructive": Boolean(validationErrors?.message),
                  })}
                />
              </FormControl>
              <FormMessage />
              {bedrockApi === "anthropic_messages" && (
                <Description>
                  For bedrock-mantle, use
                  https://bedrock-mantle.us-east-1.api.aws/anthropic with your
                  region.
                </Description>
              )}
            </FormItem>
          );
        }}
      />

      <FormField
        control={form.control}
        name="models"
        render={({ field, formState }) => {
          const validationErrors = get(formState.errors, ["models"]);

          return (
            <FormItem>
              <Label htmlFor="models">Models list</Label>
              <FormControl>
                <Input
                  id="models"
                  placeholder="Models list"
                  value={field.value}
                  onChange={(e) => field.onChange(e.target.value)}
                  className={cn({
                    "border-destructive": Boolean(validationErrors?.message),
                  })}
                />
              </FormControl>
              <FormMessage />
              <Description>
                Comma separated list of available models. Example:
                {`"${MODELS_EXAMPLES[bedrockApi]}"`}
              </Description>
            </FormItem>
          );
        }}
      />

      <CustomHeadersField form={form} />

      <AuthConfigSection
        form={form}
        staticModeFields={
          <FormField
            control={form.control}
            name="apiKey"
            render={({ field, formState }) => {
              const validationErrors = get(formState.errors, ["apiKey"]);

              return (
                <FormItem>
                  <Label htmlFor="apiKey">API key</Label>
                  <FormControl>
                    <EyeInput
                      id="apiKey"
                      placeholder="API key"
                      value={field.value}
                      onChange={(e) => field.onChange(e.target.value)}
                      className={cn({
                        "border-destructive": Boolean(
                          validationErrors?.message,
                        ),
                      })}
                    />
                  </FormControl>
                  <FormMessage />
                  <Description>
                    Click{" "}
                    <Button
                      variant="link"
                      size="sm"
                      asChild
                      className="inline px-0"
                    >
                      <a
                        href="https://docs.aws.amazon.com/bedrock/latest/userguide/getting-started-api-keys.html"
                        target="_blank"
                        rel="noreferrer"
                      >
                        here
                      </a>
                    </Button>{" "}
                    for instructions on how to create a service account and
                    assign the correct permissions.
                  </Description>
                </FormItem>
              );
            }}
          />
        }
      />
    </div>
  );
};

export default BedrockProviderDetails;
