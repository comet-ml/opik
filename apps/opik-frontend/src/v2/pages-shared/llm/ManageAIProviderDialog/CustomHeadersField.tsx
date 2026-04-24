import React from "react";
import { UseFormReturn, useFieldArray } from "react-hook-form";
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
  const { fields, append, remove } = useFieldArray({
    control: form.control,
    name,
  });

  const getFieldError = (index: number, fieldName: "key" | "value") =>
    get(form.formState.errors, [name, index, fieldName]);

  return (
    <FormItem>
      <Label>{label}</Label>
      <div className="flex flex-col gap-2">
        {fields.map((field, index) => {
          const keyError = getFieldError(index, "key");
          const valueError = getFieldError(index, "value");

          return (
            <div key={field.id} className="flex flex-col gap-1">
              <div className="flex gap-2">
                <div className="flex-1">
                  <FormField
                    control={form.control}
                    name={`${name}.${index}.key` as const}
                    render={({ field: innerField }) => (
                      <Input
                        placeholder={keyPlaceholder}
                        {...innerField}
                        className={cn("w-full", {
                          "border-destructive": Boolean(keyError),
                        })}
                      />
                    )}
                  />
                  {keyError && (
                    <p className="mt-1 text-xs text-destructive">
                      {keyError.message as string}
                    </p>
                  )}
                </div>
                <div className="flex-1">
                  <FormField
                    control={form.control}
                    name={`${name}.${index}.value` as const}
                    render={({ field: innerField }) => (
                      <Input
                        placeholder={valuePlaceholder}
                        {...innerField}
                        className={cn("w-full", {
                          "border-destructive": Boolean(valueError),
                        })}
                      />
                    )}
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
                  onClick={() => remove(index)}
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
          onClick={() => append({ key: "", value: "", id: uuidv4() })}
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
};

export default CustomHeadersField;
