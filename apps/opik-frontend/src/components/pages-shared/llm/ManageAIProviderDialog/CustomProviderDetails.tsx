import React from "react";
import { UseFormReturn } from "react-hook-form";
import { Label } from "@/components/ui/label";
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
import { Description } from "@/components/ui/description";
import { Button } from "@/components/ui/button";

type CustomProviderDetailsProps = {
  form: UseFormReturn<AIProviderFormType>;
};

const CustomProviderDetails: React.FC<CustomProviderDetailsProps> = ({
  form,
}) => {
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
                You may or may not need an API key for Custom provider,
                depending on your server configuration. If you do not need an
                API key, you can enter a dummy value, learn more in the{" "}
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
                  placeholder="Comma separated models list"
                  value={field.value}
                  onChange={(e) => field.onChange(e.target.value)}
                  className={cn({
                    "border-destructive": Boolean(validationErrors?.message),
                  })}
                />
              </FormControl>
              <FormMessage />
              <Description>
                Comma separated list of available models
              </Description>
            </FormItem>
          );
        }}
      />
    </div>
  );
};

export default CustomProviderDetails;
