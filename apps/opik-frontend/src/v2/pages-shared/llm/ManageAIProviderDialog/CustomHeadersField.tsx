import React from "react";
import { UseFormReturn } from "react-hook-form";
import { Label } from "@/ui/label";
import { AIProviderFormType } from "@/v2/pages-shared/llm/ManageAIProviderDialog/schema";
import get from "lodash/get";
import { FormField, FormItem, FormMessage } from "@/ui/form";
import { cn } from "@/lib/utils";
import { Input } from "@/ui/input";
import { Description } from "@/ui/description";
import { Button } from "@/ui/button";
import { Plus, Trash2 } from "lucide-react";
import { v4 as uuidv4 } from "uuid";

type KeyValueFieldName = "headers" | "queryParams";

type CustomHeadersFieldProps = {
  form: UseFormReturn<AIProviderFormType>;
  name?: KeyValueFieldName;
  label?: string;
  keyPlaceholder?: string;
  valuePlaceholder?: string;
  addButtonLabel?: string;
  description?: string;
};

const CustomHeadersField: React.FC<CustomHeadersFieldProps> = ({
  form,
  name = "headers",
  label = "Custom headers (optional)",
  keyPlaceholder = "Header name",
  valuePlaceholder = "Header value",
  addButtonLabel = "Add header",
  description = "Custom providers may require additional headers beyond the API key. Add them here as key-value pairs.",
}) => {
  return (
    <FormField
      control={form.control}
      name={name}
      render={({ field, formState }) => {
        const entries =
          (field.value as Array<{
            key: string;
            value: string;
            id: string;
          }>) || [];

        const addEntry = () => {
          field.onChange([...entries, { key: "", value: "", id: uuidv4() }]);
        };

        const removeEntry = (id: string) => {
          const next = entries.filter((h) => h.id !== id);
          field.onChange(next);
        };

        const updateEntry = (id: string, key: string, value: string) => {
          const next = entries.map((h) =>
            h.id === id ? { ...h, key, value } : h,
          );
          field.onChange(next);
        };

        const getFieldError = (index: number, fieldName: "key" | "value") => {
          return get(formState.errors, [name, index, fieldName]);
        };

        return (
          <FormItem>
            <Label>{label}</Label>
            <div className="flex flex-col gap-2">
              {entries.map((entry, index) => {
                const keyError = getFieldError(index, "key");
                const valueError = getFieldError(index, "value");

                return (
                  <div key={entry.id} className="flex flex-col gap-1">
                    <div className="flex gap-2">
                      <div className="flex-1">
                        <Input
                          placeholder={keyPlaceholder}
                          value={entry.key}
                          onChange={(e) =>
                            updateEntry(entry.id, e.target.value, entry.value)
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
                          placeholder={valuePlaceholder}
                          value={entry.value}
                          onChange={(e) =>
                            updateEntry(entry.id, entry.key, e.target.value)
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
                        onClick={() => removeEntry(entry.id)}
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
                onClick={addEntry}
                className="w-fit"
              >
                <Plus className="mr-1.5 size-3.5" />
                {addButtonLabel}
              </Button>
            </div>
            <Description>{description}</Description>
            <FormMessage />
          </FormItem>
        );
      }}
    />
  );
};

export default CustomHeadersField;
