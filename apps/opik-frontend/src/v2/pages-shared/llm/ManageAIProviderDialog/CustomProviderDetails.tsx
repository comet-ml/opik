import React from "react";
import { UseFormReturn } from "react-hook-form";
import { Label } from "@/ui/label";
import EyeInput from "@/shared/EyeInput/EyeInput";
import { AIProviderFormType } from "@/v2/pages-shared/llm/ManageAIProviderDialog/schema";
import get from "lodash/get";
import { FormControl, FormField, FormItem, FormMessage } from "@/ui/form";
import { cn } from "@/lib/utils";
import { buildDocsUrl } from "@/v2/lib/utils";
import { Input } from "@/ui/input";
import { Description } from "@/ui/description";
import { Button } from "@/ui/button";
import { Switch } from "@/ui/switch";
import { PROVIDERS } from "@/constants/providers";
import { PROVIDER_TYPE } from "@/types/providers";
import CustomHeadersField from "./CustomHeadersField";

type CustomProviderDetailsProps = {
  form: UseFormReturn<AIProviderFormType>;
  isEdit?: boolean;
};

const CustomProviderDetails: React.FC<CustomProviderDetailsProps> = ({
  form,
  isEdit = false,
}) => {
  return (
    <div className="flex flex-col gap-4 pb-4">
      <p className="comet-body-s text-muted-slate">
        {PROVIDERS[PROVIDER_TYPE.CUSTOM].description}
      </p>
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
                    placeholder="ollama"
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
                  {
                    'A unique identifier for this provider instance (e.g., "ollama", "vLLM", "LM-Studio").'
                  }
                </Description>
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
              <Label htmlFor="url">URL</Label>
              <FormControl>
                <Input
                  id="url"
                  placeholder={"https://vllm.example.com/v1"}
                  value={field.value}
                  onChange={(e) => field.onChange(e.target.value)}
                  className={cn({
                    "border-destructive": Boolean(validationErrors?.message),
                  })}
                />
              </FormControl>
              <FormMessage />
              <Description>
                {
                  "Use `{model}` as a placeholder in the URL if your gateway expects the model name in the path — Opik substitutes the selected model at request time. The model name is interpolated raw, so values containing `/` (e.g. HuggingFace-style names) will become extra path segments."
                }
              </Description>
            </FormItem>
          );
        }}
      />

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
                    "border-destructive": Boolean(validationErrors?.message),
                  })}
                />
              </FormControl>
              <FormMessage />
              <Description>
                Custom providers may not require an API key, depending on your
                server setup. Learn more in the{" "}
                <Button
                  variant="link"
                  size="sm"
                  asChild
                  className="inline px-0"
                >
                  <a
                    href={buildDocsUrl("/development/playground")}
                    target="_blank"
                    rel="noreferrer"
                  >
                    documentation
                  </a>
                </Button>
                .
              </Description>
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
                Comma-separated list of available models. Example:{" "}
                {`"gpt-4o, gpt-4o-mini, llama-3.1-70b"`}
              </Description>
            </FormItem>
          );
        }}
      />

      <CustomHeadersField form={form} />

      <CustomHeadersField
        form={form}
        name="queryParams"
        label="Query parameters (optional)"
        keyPlaceholder="Parameter name"
        valuePlaceholder="Parameter value"
        addButtonLabel="Add query parameter"
        description="Appended to every outgoing request URL. Some gateways require a version parameter such as api-version=2024-08-01-preview."
      />

      <FormField
        control={form.control}
        name="authHeaderName"
        render={({ field, formState }) => {
          const validationErrors = get(formState.errors, ["authHeaderName"]);

          return (
            <FormItem>
              <Label htmlFor="authHeaderName">
                Auth header name (optional)
              </Label>
              <FormControl>
                <Input
                  id="authHeaderName"
                  placeholder="api-key"
                  value={field.value ?? ""}
                  onChange={(e) => field.onChange(e.target.value)}
                  className={cn({
                    "border-destructive": Boolean(validationErrors?.message),
                  })}
                />
              </FormControl>
              <FormMessage />
              <Description>
                If set, the API key is sent as <code>{"{name}: <key>"}</code> in
                addition to the default <code>Authorization: Bearer</code>{" "}
                header.
              </Description>
            </FormItem>
          );
        }}
      />

      <FormField
        control={form.control}
        name="suppressDefaultAuth"
        render={({ field }) => (
          <FormItem>
            <div className="flex items-center gap-3">
              <Switch
                id="suppressDefaultAuth"
                checked={Boolean(field.value)}
                onCheckedChange={field.onChange}
              />
              <Label htmlFor="suppressDefaultAuth">
                Suppress default Authorization header
              </Label>
            </div>
            <Description>
              Turn on only if your gateway rejects requests that include{" "}
              <code>Authorization: Bearer</code>.
            </Description>
          </FormItem>
        )}
      />
    </div>
  );
};

export default CustomProviderDetails;
