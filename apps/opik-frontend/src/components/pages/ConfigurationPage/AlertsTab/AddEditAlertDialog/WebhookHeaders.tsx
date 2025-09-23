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
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
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
      <Label className="flex items-center gap-1">
        Headers
        <ExplainerIcon description="Optional HTTP headers to include in webhook requests" />
      </Label>

      {headerFields.length > 0 && (
        <div className="flex flex-col gap-2">
          <div className="flex items-center gap-2">
            <div className="flex-1">
              <Label className="comet-body-s-accented text-foreground">
                Key
              </Label>
            </div>
            <div className="flex-1">
              <Label className="comet-body-s-accented text-foreground">
                Value
              </Label>
            </div>
            <div className="size-10"></div>
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

              <Button
                type="button"
                variant="ghost"
                size="sm"
                className="size-10 p-0"
                onClick={() => removeHeader(index)}
              >
                <X className="size-4" />
              </Button>
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
