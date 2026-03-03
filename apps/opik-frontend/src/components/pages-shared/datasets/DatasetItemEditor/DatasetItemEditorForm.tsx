import React, { useEffect, useMemo } from "react";
import { FormProvider, useForm, useFormContext } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import TextareaAutosize from "react-textarea-autosize";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import { cn } from "@/lib/utils";
import { DatasetField, FIELD_TYPE } from "./hooks/useDatasetItemData";
import JsonFieldEditor from "./JsonFieldEditor";
import { createDynamicSchema } from "./hooks/useDatasetItemFormHelpers";

interface DatasetItemEditorFormProps {
  formId: string;
  fields: DatasetField[];
  onSubmit?: (data: Record<string, unknown>) => void;
  setHasUnsavedChanges?: (value: boolean) => void;
  onFieldChange?: (data: Record<string, unknown>) => void;
}

const FieldInput: React.FC<{ field: DatasetField }> = ({ field }) => {
  const form = useFormContext<Record<string, unknown>>();
  const fieldValue = form.watch(field.key);

  if (field.type === FIELD_TYPE.COMPLEX) {
    return <JsonFieldEditor fieldName={field.key} isEditing={true} />;
  }

  const displayValue =
    fieldValue !== null && fieldValue !== undefined ? String(fieldValue) : "";

  return (
    <div className="flex min-h-0 w-full flex-1">
      <TextareaAutosize
        value={displayValue}
        onChange={(e) =>
          form.setValue(field.key, e.target.value, { shouldDirty: true })
        }
        placeholder="Enter text for this field…"
        className={cn(
          "flex w-full rounded-md resize-none border border-border bg-background px-3 py-2 text-sm text-foreground ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50 font-mono hover:shadow-sm focus-visible:border-primary",
        )}
        minRows={1}
      />
    </div>
  );
};

const DatasetItemEditorForm: React.FC<DatasetItemEditorFormProps> = ({
  formId,
  fields,
  onSubmit,
  setHasUnsavedChanges,
  onFieldChange,
}) => {
  // Create schema from fields
  const schema = useMemo(() => createDynamicSchema(fields), [fields]);

  // Create initial values from fields
  const initialValues = useMemo(() => {
    const values: Record<string, unknown> = {};
    fields.forEach((field) => {
      // For COMPLEX fields, stringify the value for CodeMirror
      if (field.type === FIELD_TYPE.COMPLEX) {
        values[field.key] = JSON.stringify(field.value, null, 2);
      } else {
        values[field.key] = field.value;
      }
    });
    return values;
  }, [fields]);

  // Initialize form
  const form = useForm<Record<string, unknown>>({
    resolver: zodResolver(schema),
    defaultValues: initialValues,
  });

  // Autosave mode: watch for changes and trigger autosave
  useEffect(() => {
    if (onFieldChange) {
      const subscription = form.watch((value) => {
        onFieldChange(value as Record<string, unknown>);
      });
      return () => subscription.unsubscribe();
    }
  }, [form, onFieldChange]);

  // Manual save mode: sync dirty state with parent
  useEffect(() => {
    if (setHasUnsavedChanges) {
      setHasUnsavedChanges(form.formState.isDirty);
    }
  }, [form.formState.isDirty, setHasUnsavedChanges]);

  // Handle form submission (only for manual save mode)
  const handleSubmit = (data: Record<string, unknown>) => {
    onSubmit?.(data);
  };

  return (
    <FormProvider {...form}>
      <form id={formId} onSubmit={form.handleSubmit(handleSubmit)}>
        <Accordion
          type="multiple"
          className="w-full"
          defaultValue={fields.map((f) => f.key)}
        >
          {fields.map((field) => (
            <AccordionItem key={field.key} value={field.key}>
              <AccordionTrigger className="comet-body-s-accented pl-0 text-foreground">
                {field.key}
              </AccordionTrigger>
              <AccordionContent>
                <FieldInput field={field} />
              </AccordionContent>
            </AccordionItem>
          ))}
        </Accordion>
      </form>
    </FormProvider>
  );
};

export default DatasetItemEditorForm;
