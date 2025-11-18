import React, { useCallback } from "react";
import { FormProvider } from "react-hook-form";
import { Pencil } from "lucide-react";
import { ColumnData } from "@/types/shared";
import { DatasetItem } from "@/types/datasets";
import ResizableSidePanel from "@/components/shared/ResizableSidePanel/ResizableSidePanel";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import { Button } from "@/components/ui/button";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import Loader from "@/components/shared/Loader/Loader";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { useDatasetItemEditorState } from "./useDatasetItemEditorState";
import { useDatasetItemNavigation } from "./useDatasetItemNavigation";
import {
  useDatasetItemData,
  FIELD_TYPE,
  DatasetField,
} from "./useDatasetItemData";
import JsonFieldEditor from "./JsonFieldEditor";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";

interface DatasetItemEditorProps {
  datasetItemId: string;
  columns: ColumnData<DatasetItem>[];
  onClose: () => void;
  isOpen: boolean;
  rows: DatasetItem[];
  setActiveRowId: (id: string) => void;
}

const truncateId = (id: string): string => {
  if (id.length <= 12) return id;
  return `${id.slice(0, 4)}...${id.slice(-4)}`;
};

const DatasetItemEditor: React.FC<DatasetItemEditorProps> = ({
  datasetItemId,
  columns,
  onClose,
  isOpen,
  rows,
  setActiveRowId,
}) => {
  const { fields, isPending, datasetItem } = useDatasetItemData({
    datasetItemId,
    columns,
  });

  const {
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
  } = useDatasetItemEditorState({ datasetItem, fields });

  const { horizontalNavigation } = useDatasetItemNavigation({
    activeRowId: datasetItemId,
    rows,
    setActiveRowId,
    checkUnsavedChanges: requestConfirmIfNeeded,
  });

  const handleCloseWithConfirm = useCallback(() => {
    requestConfirmIfNeeded(onClose);
  }, [requestConfirmIfNeeded, onClose]);

  const renderFieldInput = (field: DatasetField) => {
    const fieldValue = form.watch(field.id);

    if (field.type === FIELD_TYPE.COMPLEX) {
      return (
        <JsonFieldEditor
          value={fieldValue as Record<string, unknown>}
          onChange={(value) =>
            form.setValue(field.id, value, { shouldDirty: true })
          }
          isEditing={isEditing}
          fieldName={field.id}
        />
      );
    }

    const displayValue =
      fieldValue !== null && fieldValue !== undefined ? String(fieldValue) : "";

    return (
      <Input
        value={displayValue}
        onChange={(e) =>
          form.setValue(field.id, e.target.value, { shouldDirty: true })
        }
        readOnly={!isEditing}
        className={cn(
          "min-h-[40px] font-mono",
          !isEditing && "cursor-text bg-[var(--codemirror-background)]",
        )}
      />
    );
  };

  return (
    <>
      <ResizableSidePanel
        panelId="dataset-item-editor"
        entity="item"
        open={isOpen}
        onClose={handleCloseWithConfirm}
        horizontalNavigation={horizontalNavigation}
      >
        {isPending ? (
          <div className="flex size-full items-center justify-center">
            <Loader />
          </div>
        ) : (
          <FormProvider {...form}>
            <div className="relative size-full overflow-y-auto p-6">
              <div className="flex items-center justify-between border-b pb-6">
                <TooltipWrapper content={datasetItemId}>
                  <div className="text-lg font-semibold">
                    Dataset item{" "}
                    <span className="comet-body-s text-muted-slate">
                      {truncateId(datasetItemId)}
                    </span>
                  </div>
                </TooltipWrapper>
                <div className="flex items-center gap-2">
                  {!isEditing && (
                    <Button variant="default" size="sm" onClick={handleEdit}>
                      <Pencil className="mr-2 size-4" />
                      Edit
                    </Button>
                  )}
                  {isEditing && (
                    <>
                      <Button
                        variant="default"
                        size="sm"
                        onClick={handleSave}
                        disabled={!hasUnsavedChanges}
                      >
                        Save changes
                      </Button>
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={handleDiscard}
                      >
                        Cancel
                      </Button>
                    </>
                  )}
                </div>
              </div>
              <Accordion
                key={datasetItemId}
                type="multiple"
                className="w-full"
                defaultValue={fields.map((f) => f.id)}
              >
                {fields.map((field) => (
                  <AccordionItem key={field.id} value={field.id}>
                    <AccordionTrigger className="pl-0">
                      {field.label}
                    </AccordionTrigger>
                    <AccordionContent>
                      {renderFieldInput(field)}
                    </AccordionContent>
                  </AccordionItem>
                ))}
              </Accordion>
            </div>
          </FormProvider>
        )}
      </ResizableSidePanel>

      <ConfirmDialog
        open={showConfirmDialog}
        setOpen={setShowConfirmDialog}
        onConfirm={confirm}
        onCancel={cancel}
        title="Discard changes?"
        description="You made some changes that haven't been saved yet. Do you want to keep editing or discard them?"
        confirmText="Discard changes"
        cancelText="Keep editing"
        confirmButtonVariant="destructive"
      />
    </>
  );
};

export default DatasetItemEditor;
