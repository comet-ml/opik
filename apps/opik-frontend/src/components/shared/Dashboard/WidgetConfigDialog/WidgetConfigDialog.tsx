import React, { useState, useEffect, useRef } from "react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { ChevronLeft } from "lucide-react";
import {
  useDashboardStore,
  selectWidgetResolver,
  selectSetPreviewWidget,
} from "@/store/DashboardStore";
import WidgetConfigDialogAddStep from "./WidgetConfigDialogAddStep";
import {
  DashboardWidget,
  WidgetConfigDialogProps,
  WidgetEditorHandle,
} from "@/types/dashboard";
import { createDefaultWidgetConfig } from "@/lib/dashboard/utils";

enum DialogStep {
  ADD = "add",
  EDIT = "edit",
}

const WidgetConfigDialog: React.FunctionComponent<WidgetConfigDialogProps> = ({
  open,
  onOpenChange,
  sectionId,
  widgetId,
  onSave,
}) => {
  const isEditMode = !!widgetId;
  const [currentStep, setCurrentStep] = useState<DialogStep>(
    isEditMode ? DialogStep.EDIT : DialogStep.ADD,
  );
  const editorRef = useRef<WidgetEditorHandle>(null);

  const widgetResolver = useDashboardStore(selectWidgetResolver);
  const getWidgetById = useDashboardStore((state) => state.getWidgetById);
  const previewWidget = useDashboardStore((state) => state.previewWidget);
  const setPreviewWidget = useDashboardStore(selectSetPreviewWidget);

  const EditorComponent =
    previewWidget?.type && widgetResolver
      ? widgetResolver({ type: previewWidget.type })?.Editor
      : null;

  useEffect(() => {
    if (!isEditMode && open) {
      setCurrentStep(DialogStep.ADD);
    } else if (isEditMode && widgetId) {
      setCurrentStep(DialogStep.EDIT);
      const widget = getWidgetById(sectionId, widgetId);
      if (widget) {
        setPreviewWidget(widget);
      }
    }
  }, [isEditMode, open, widgetId, sectionId, getWidgetById, setPreviewWidget]);

  useEffect(() => {
    if (!open) {
      setPreviewWidget(null);
      setCurrentStep(isEditMode ? DialogStep.EDIT : DialogStep.ADD);
    }
  }, [open, setPreviewWidget, isEditMode]);

  const handleSelectWidget = (widgetType: string) => {
    const newWidget = createDefaultWidgetConfig(widgetType, widgetResolver);
    setPreviewWidget({ ...newWidget, id: "preview" } as DashboardWidget);
    setCurrentStep(DialogStep.EDIT);
  };

  const handleBack = () => {
    if (currentStep === DialogStep.EDIT && !isEditMode) {
      setCurrentStep(DialogStep.ADD);
    }
  };

  const handleSave = async () => {
    if (!previewWidget) return;
    const isValid = await editorRef.current?.submit();
    if (isValid) {
      onSave(previewWidget);
      onOpenChange(false);
    }
  };

  const handleCancel = () => {
    onOpenChange(false);
  };

  const dialogTitle = isEditMode ? "Edit widget" : "Add widget";
  const dialogDescription =
    currentStep === DialogStep.ADD
      ? "Choose the type of widget you want to add. Widgets can use project metrics, or simple text to add context."
      : isEditMode
        ? "Adjust the data, visualization, or settings for this widget. Changes will update the dashboard automatically."
        : "Adjust the data, visualization, or settings for this widget.";

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-6xl">
        <DialogHeader>
          <DialogTitle>{dialogTitle}</DialogTitle>
          <DialogDescription>{dialogDescription}</DialogDescription>
        </DialogHeader>

        {currentStep === DialogStep.ADD && (
          <div className="max-h-[50vh] overflow-y-auto">
            <WidgetConfigDialogAddStep onSelectWidget={handleSelectWidget} />
          </div>
        )}

        {currentStep === DialogStep.EDIT && EditorComponent && (
          <EditorComponent ref={editorRef} />
        )}

        <DialogFooter className="flex flex-row justify-between gap-2 border-t pt-4 sm:flex-row sm:justify-between">
          <div>
            {currentStep === DialogStep.EDIT && !isEditMode && (
              <Button variant="outline" onClick={handleBack}>
                <ChevronLeft className="mr-2 size-4" />
                Back
              </Button>
            )}
          </div>
          <div className="flex gap-2">
            <Button variant="outline" onClick={handleCancel}>
              Cancel
            </Button>
            {currentStep === DialogStep.EDIT && (
              <Button onClick={handleSave}>
                {isEditMode ? "Save changes" : "Add widget"}
              </Button>
            )}
          </div>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default WidgetConfigDialog;
