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
import { cn } from "@/lib/utils";
import { Input } from "@/components/ui/input";
import { Description } from "@/components/ui/description";
import { Button } from "@/components/ui/button";
import { Plus, Trash2 } from "lucide-react";
import { v4 as uuidv4 } from "uuid";

type BedrockProviderDetailsProps = {
  form: UseFormReturn<AIProviderFormType>;
  isEdit?: boolean;
};

const BedrockProviderDetails: React.FC<BedrockProviderDetailsProps> = ({
  form,
  isEdit = false,
}) => {
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
        name="url"
        render={({ field, formState }) => {
          const validationErrors = get(formState.errors, ["url"]);

          return (
            <FormItem>
              <Label htmlFor="url">URL</Label>
              <FormControl>
                <Input
                  id="url"
                  placeholder="https://bedrock-runtime.us-east-1.amazonaws.com/openai/v1"
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
                for instructions on how to create a service account and assign
                the correct permissions.
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
                Comma separated list of available models. Example:
                {`"openai.gpt-oss-20b-1:0,mistral.ministral-3-3b-instruct"`}
              </Description>
            </FormItem>
          );
        }}
      />

      <FormField
        control={form.control}
        name="headers"
        render={({ field, formState }) => {
          const headers = field.value || [];

          const addHeader = () => {
            field.onChange([...headers, { key: "", value: "", id: uuidv4() }]);
          };

          const removeHeader = (id: string) => {
            const newHeaders = headers.filter((h) => h.id !== id);
            // Always set to array (even if empty) so backend can clear headers
            field.onChange(newHeaders);
          };

          const updateHeader = (id: string, key: string, value: string) => {
            const newHeaders = headers.map((h) =>
              h.id === id ? { ...h, key, value } : h,
            );
            field.onChange(newHeaders);
          };

          // Get validation errors for individual headers
          const getHeaderError = (index: number, field: "key" | "value") => {
            return get(formState.errors, ["headers", index, field]);
          };

          return (
            <FormItem>
              <Label>Custom headers (optional)</Label>
              <div className="flex flex-col gap-2">
                {headers.map((header, index) => {
                  const keyError = getHeaderError(index, "key");
                  const valueError = getHeaderError(index, "value");

                  return (
                    <div key={header.id} className="flex flex-col gap-1">
                      <div className="flex gap-2">
                        <div className="flex-1">
                          <Input
                            placeholder="Header name"
                            value={header.key}
                            onChange={(e) =>
                              updateHeader(
                                header.id,
                                e.target.value,
                                header.value,
                              )
                            }
                            className={cn("w-full", {
                              "border-destructive": Boolean(keyError),
                            })}
                          />
                          {keyError && (
                            <p className="mt-1 text-xs text-destructive">
                              {keyError.message as string}
                            </p>
                          )}
                        </div>
                        <div className="flex-1">
                          <Input
                            placeholder="Header value"
                            value={header.value}
                            onChange={(e) =>
                              updateHeader(
                                header.id,
                                header.key,
                                e.target.value,
                              )
                            }
                            className={cn("w-full", {
                              "border-destructive": Boolean(valueError),
                            })}
                          />
                          {valueError && (
                            <p className="mt-1 text-xs text-destructive">
                              {valueError.message as string}
                            </p>
                          )}
                        </div>
                        <Button
                          type="button"
                          variant="ghost"
                          size="icon"
                          onClick={() => removeHeader(header.id)}
                          className="shrink-0"
                        >
                          <Trash2 className="comet-body-s" />
                        </Button>
                      </div>
                    </div>
                  );
                })}
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={addHeader}
                  className="w-fit"
                >
                  <Plus className="mr-1.5 size-3.5" />
                  Add header
                </Button>
              </div>
              <Description>
                Custom providers may require additional headers beyond the API
                key. Add them here as key-value pairs.
              </Description>
              <FormMessage />
            </FormItem>
          );
        }}
      />
    </div>
  );
};

export default BedrockProviderDetails;
