import { useCallback, useEffect, useState, useMemo } from "react";
import { useForm, UseFormReturn } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useConfirmAction } from "@/components/shared/ConfirmDialog/useConfirmAction";
import { DatasetItem } from "@/types/datasets";
import { DatasetField } from "./useDatasetItemData";

interface UseDatasetItemEditorStateParams {
  datasetItem: DatasetItem | undefined;
  fields: DatasetField[];
}

interface UseDatasetItemEditorStateReturn {
  isEditing: boolean;
  hasUnsavedChanges: boolean;
  showConfirmDialog: boolean;
  setShowConfirmDialog: (show: boolean) => void;
  handleEdit: () => void;
  handleSave: () => void;
  handleDiscard: () => void;
  requestConfirmIfNeeded: (action: () => void) => void;
  confirm: () => void;
  cancel: () => void;
  form: UseFormReturn<Record<string, unknown>>;
}

const createDynamicSchema = (data: Record<string, unknown> | undefined) => {
  if (!data) {
    return z.object({});
  }

  const schemaShape: Record<string, z.ZodTypeAny> = {};

  Object.keys(data).forEach((key) => {
    schemaShape[key] = z.any();
  });

  return z.object(schemaShape);
};

export const useDatasetItemEditorState = ({
  datasetItem,
  fields,
}: UseDatasetItemEditorStateParams): UseDatasetItemEditorStateReturn => {
  const [isEditing, setIsEditing] = useState(false);

  const schema = useMemo(
    () => createDynamicSchema(datasetItem?.data as Record<string, unknown>),
    [datasetItem],
  );

  const initialValues = useMemo(() => {
    const values: Record<string, unknown> = {};
    fields.forEach((field) => {
      values[field.id] = field.value;
    });
    return values;
  }, [fields]);

  const form = useForm<Record<string, unknown>>({
    resolver: zodResolver(schema),
    defaultValues: initialValues,
  });

  const {
    isOpen: showConfirmDialog,
    setIsOpen: setShowConfirmDialog,
    requestConfirm,
    confirm,
    cancel,
  } = useConfirmAction();

  const hasUnsavedChanges = form.formState.isDirty;

  useEffect(() => {
    if (fields.length > 0) {
      const values: Record<string, unknown> = {};
      fields.forEach((field) => {
        values[field.id] = field.value;
      });
      form.reset(values);
    }
  }, [fields, form]);

  const handleEdit = useCallback(() => {
    setIsEditing(true);
  }, []);

  const handleSave = useCallback(() => {
    // TODO: Implement actual save logic
    const formData = form.getValues();

    console.log("Saving data:", formData);
    setIsEditing(false);
    form.reset(formData);
  }, [form]);

  const handleDiscard = useCallback(() => {
    form.reset();
    setIsEditing(false);
  }, [form]);

  const requestConfirmIfNeeded = useCallback(
    (action: () => void) => {
      if (hasUnsavedChanges) {
        requestConfirm(() => {
          action();
          form.reset();
          setIsEditing(false);
        });
      } else {
        action();
        setIsEditing(false);
      }
    },
    [hasUnsavedChanges, requestConfirm, form],
  );

  return {
    isEditing,
    hasUnsavedChanges,
    showConfirmDialog,
    setShowConfirmDialog,
    handleEdit,
    handleSave,
    handleDiscard,
    requestConfirmIfNeeded,
    confirm,
    cancel,
    form,
  };
};
