import React from "react";
import {
  ArrayPath,
  FieldArray,
  FieldValues,
  UseFormReturn,
  useFieldArray,
} from "react-hook-form";
import get from "lodash/get";
import { Plus, Trash2 } from "lucide-react";

import { cn } from "@/lib/utils";
import { Button } from "@/ui/button";
import { Label } from "@/ui/label";
import { FormControl, FormField, FormItem, FormMessage } from "@/ui/form";
import { Input } from "@/ui/input";
import { Description } from "@/ui/description";

/// Generic key/value array field editor backed by React Hook Form's
/// `useFieldArray`. Reused by any form that stores a list of `{key, value}`
/// rows (custom HTTP headers, query parameters, webhook headers, etc.), so
/// that schema / styling / add-remove behaviour lives in one place.
///
/// The `newItem` prop lets each consumer control the shape of freshly-appended
/// rows, because different schemas may require extra fields (e.g. a separate
/// `id` string to satisfy a Zod contract).
type KeyValueFieldArrayProps<T extends FieldValues> = {
  form: UseFormReturn<T>;
  name: ArrayPath<T>;
  label: string;
  description?: React.ReactNode;
  keyPlaceholder?: string;
  valuePlaceholder?: string;
  addButtonLabel?: string;
  showColumnHeaders?: boolean;
  newItem: () => FieldArray<T, ArrayPath<T>>;
};

function KeyValueFieldArray<T extends FieldValues>({
  form,
  name,
  label,
  description,
  keyPlaceholder = "Key",
  valuePlaceholder = "Value",
  addButtonLabel = "Add",
  showColumnHeaders = false,
  newItem,
}: KeyValueFieldArrayProps<T>) {
  const { fields, append, remove } = useFieldArray<T, ArrayPath<T>>({
    control: form.control,
    name,
  });

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-1">
        <Label>{label}</Label>
        {description && <Description>{description}</Description>}
      </div>

      {showColumnHeaders && fields.length > 0 && (
        <div className="flex items-center">
          <div className="flex-1">
            <Label className="comet-body-s-accented">Key</Label>
          </div>
          <div className="flex-1">
            <Label className="comet-body-s-accented">Value</Label>
          </div>
          <div className="w-10"></div>
        </div>
      )}

      {fields.length > 0 && (
        <div className="flex flex-col gap-2">
          {fields.map((field, index) => {
            // React Hook Form stores nested errors by path segments (e.g.
            // `errors.provider.headers[0].key` for a dotted `name`). Split the
            // `ArrayPath` on `.` so `get` traverses each segment rather than
            // treating the whole string as a single literal key.
            const nameSegments = (name as string).split(".");
            const keyError = get(form.formState.errors, [
              ...nameSegments,
              index,
              "key",
            ]);
            const valueError = get(form.formState.errors, [
              ...nameSegments,
              index,
              "value",
            ]);

            return (
              <div key={field.id} className="flex items-start gap-2">
                <FormField
                  control={form.control}
                  // eslint-disable-next-line @typescript-eslint/no-explicit-any
                  name={`${name}.${index}.key` as any}
                  render={({ field: innerField }) => (
                    <FormItem className="flex-1">
                      <FormControl>
                        <Input
                          placeholder={keyPlaceholder}
                          className={cn({
                            "border-destructive": Boolean(keyError),
                          })}
                          {...innerField}
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
                <FormField
                  control={form.control}
                  // eslint-disable-next-line @typescript-eslint/no-explicit-any
                  name={`${name}.${index}.value` as any}
                  render={({ field: innerField }) => (
                    <FormItem className="flex-1">
                      <FormControl>
                        <Input
                          placeholder={valuePlaceholder}
                          className={cn({
                            "border-destructive": Boolean(valueError),
                          })}
                          {...innerField}
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
                <div className="flex items-center self-stretch">
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
        </div>
      )}

      <Button
        type="button"
        variant="outline"
        size="sm"
        className="w-fit"
        onClick={() => append(newItem())}
      >
        <Plus className="mr-1.5 size-3.5" />
        {addButtonLabel}
      </Button>
    </div>
  );
}

export default KeyValueFieldArray;
