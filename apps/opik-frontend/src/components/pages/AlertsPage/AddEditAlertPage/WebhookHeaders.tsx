import React from "react";
import { UseFormReturn, useFieldArray } from "react-hook-form";
import get from "lodash/get";
import { Plus, X } from "lucide-react";

import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import {
  FormControl,
  FormField,
  FormItem,
  FormMessage,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { Description } from "@/components/ui/description";
import { AlertFormType } from "./schema";

type WebhookHeadersProps = {
  form: UseFormReturn<AlertFormType>;
};

const WebhookHeaders: React.FC<WebhookHeadersProps> = ({ form }) => {
  const {
    fields: headerFields,
    append: appendHeader,
    remove: removeHeader,
  } = useFieldArray({
    control: form.control,
    name: "headers",
  });

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-1">
        <Label>Headers (optional)</Label>
        <Description>
          Specify custom HTTP headers to include with each webhook request. Use
          them for authentication, content type specification, or any other
          required metadata.
        </Description>
      </div>

      {headerFields.length > 0 && (
        <div className="flex flex-col gap-2">
          <div className="flex items-center">
            <div className="flex-1">
              <Label className="comet-body-s-accented">Key</Label>
            </div>
            <div className="flex-1">
              <Label className="comet-body-s-accented">Value</Label>
            </div>
            <div className="w-10"></div>
          </div>

          {headerFields.map((header, index) => (
            <div key={header.id} className="flex items-start gap-2">
              <FormField
                control={form.control}
                name={`headers.${index}.key`}
                render={({ field, formState }) => {
                  const validationErrors = get(formState.errors, [
                    "headers",
                    index,
                    "key",
                  ]);
                  return (
                    <FormItem className="flex-1">
                      <FormControl>
                        <Input
                          className={cn({
                            "border-destructive": Boolean(
                              validationErrors?.message,
                            ),
                          })}
                          placeholder="Key"
                          {...field}
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  );
                }}
              />

              <FormField
                control={form.control}
                name={`headers.${index}.value`}
                render={({ field, formState }) => {
                  const validationErrors = get(formState.errors, [
                    "headers",
                    index,
                    "value",
                  ]);
                  return (
                    <FormItem className="flex-1">
                      <FormControl>
                        <Input
                          className={cn({
                            "border-destructive": Boolean(
                              validationErrors?.message,
                            ),
                          })}
                          placeholder="Value"
                          {...field}
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  );
                }}
              />
              <div className="flex items-center self-stretch">
                <Button
                  type="button"
                  variant="minimal"
                  size="icon-xs"
                  onClick={() => removeHeader(index)}
                >
                  <X />
                </Button>
              </div>
            </div>
          ))}
        </div>
      )}

      <Button
        type="button"
        variant="outline"
        size="sm"
        className="w-fit"
        onClick={() => appendHeader({ key: "", value: "" })}
      >
        <Plus className="mr-1 size-4" />
        Add header
      </Button>
    </div>
  );
};

export default WebhookHeaders;
