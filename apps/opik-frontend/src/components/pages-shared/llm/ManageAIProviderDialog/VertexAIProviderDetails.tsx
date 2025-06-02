import React from "react";
import { UseFormReturn } from "react-hook-form";
import { Button } from "@/components/ui/button";
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
import { buildDocsUrl, cn } from "@/lib/utils";
import { Input } from "@/components/ui/input";

type VertexAIProviderDetailsProps = {
  form: UseFormReturn<AIProviderFormType>;
};

const VertexAIProviderDetails: React.FC<VertexAIProviderDetailsProps> = ({
  form,
}) => {
  const providerName = PROVIDERS[PROVIDER_TYPE.VERTEX_AI].label;
  const apiKeyLabel = `${providerName} API Key`;

  return (
    <div className="flex flex-col gap-2 pb-4">
      <FormField
        control={form.control}
        name="location"
        render={({ field, formState }) => {
          const validationErrors = get(formState.errors, ["location"]);

          return (
            <FormItem>
              <Label htmlFor="location">Location</Label>
              <FormControl>
                <Input
                  id="location"
                  placeholder="Location"
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

      <FormField
        control={form.control}
        name="apiKey"
        render={({ field, formState }) => {
          const validationErrors = get(formState.errors, ["apiKey"]);

          return (
            <FormItem className="mt-2">
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

      <span className="comet-body-s mt-1 text-light-slate">
        <Button variant="link" size="sm" asChild className="px-0">
          <a
            href={buildDocsUrl("/tracing/integrations/vertexAi")}
            target="_blank"
            rel="noreferrer"
          >
            Click here
          </a>
        </Button>{" "}
        for instructions on how to create a service account and assign the
        correct permissions.
      </span>
    </div>
  );
};

export default VertexAIProviderDetails;
