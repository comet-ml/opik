import React from "react";
import { UseFormReturn } from "react-hook-form";
import { Label } from "@/components/ui/label";
import { PROVIDER_TYPE } from "@/types/providers";
import { PROVIDERS } from "@/constants/providers";
import { AIProviderFormType } from "@/components/pages-shared/llm/ManageAIProviderDialog/schema";
import get from "lodash/get";
import {
  FormControl,
  FormField,
  FormItem,
  FormMessage,
} from "@/components/ui/form";
import { buildDocsUrl, cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Description } from "@/components/ui/description";

type LocalAIProviderDetailsProps = {
  provider: PROVIDER_TYPE | "";
  form: UseFormReturn<AIProviderFormType>;
};

const LocalAIProviderDetails: React.FC<LocalAIProviderDetailsProps> = ({
  provider,
  form,
}) => {
  const providerName = (provider && PROVIDERS[provider]?.label + " ") || "";
  const urlLabel = `${providerName}URL`;

  return (
    <>
      <div className="flex flex-col gap-2">
        <FormField
          control={form.control}
          name="url"
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, ["url"]);

            return (
              <FormItem>
                <Label htmlFor="url">{urlLabel}</Label>
                <Description>
                  To use {providerName}you will need to configure the Opik proxy
                  to avoid network issues, learn more in the{" "}
                  <Button
                    variant="link"
                    size="sm"
                    asChild
                    className="inline px-0"
                  >
                    <a
                      href={buildDocsUrl("/playground")}
                      target="_blank"
                      rel="noreferrer"
                    >
                      documentation
                    </a>
                  </Button>
                  .
                </Description>
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
      <div className="flex flex-col gap-2">
        <FormField
          control={form.control}
          name="models"
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, ["models"]);

            return (
              <FormItem>
                <Label htmlFor="models">Models list</Label>
                <Description>
                  Comma separated list of available models
                </Description>
                <FormControl>
                  <Input
                    id="models"
                    placeholder="Comma separated models list"
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
    </>
  );
};

export default LocalAIProviderDetails;
