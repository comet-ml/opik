import React, { useState, useEffect, useRef } from "react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import {
  useDashboardStore,
  selectWidgetResolver,
  selectSetPreviewWidget,
} from "@/store/DashboardStore";
import WidgetConfigDialogAddStep from "./WidgetConfigDialogAddStep";
import WidgetConfigDialogEditStep from "./WidgetConfigDialogEditStep";
import {
  AddWidgetConfig,
  WIDGET_TYPE,
  DashboardWidget,
  WidgetConfigDialogProps,
  WidgetEditorHandle,
} from "@/types/dashboard";
import { createDefaultWidgetConfig } from "@/lib/dashboard/utils";

enum DialogStep {
  ADD = "add",
  EDIT = "edit",
}

const convertWidgetToAddConfig = (widget: DashboardWidget): AddWidgetConfig => {
  return {
    type: widget.type as WIDGET_TYPE,
    title: widget.title,
    subtitle: widget.subtitle,
    config: widget.config,
  } as AddWidgetConfig;
};

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
  const [widgetData, setWidgetData] = useState<AddWidgetConfig | null>(null);
  const editorRef = useRef<WidgetEditorHandle>(null);

  const widgetResolver = useDashboardStore(selectWidgetResolver);
  const getWidgetById = useDashboardStore((state) => state.getWidgetById);
  const setPreviewWidget = useDashboardStore(selectSetPreviewWidget);

  const editorComponent =
    widgetData?.type && widgetResolver
      ? widgetResolver(widgetData.type)?.Editor || null
      : null;

  useEffect(() => {
    if (!isEditMode && open) {
      setCurrentStep(DialogStep.ADD);
    } else if (isEditMode && widgetId) {
      setCurrentStep(DialogStep.EDIT);
      const widget = getWidgetById(sectionId, widgetId);
      if (widget) {
        setWidgetData(convertWidgetToAddConfig(widget));
      }
    }
  }, [isEditMode, open, widgetId, sectionId, getWidgetById]);

  useEffect(() => {
    if (open && currentStep === DialogStep.EDIT && widgetData) {
      const previewWidget: DashboardWidget = {
        id: "preview",
        title: widgetData.title || "",
        subtitle: widgetData.subtitle,
        type: widgetData.type,
        config: widgetData.config || {},
      };
      setPreviewWidget(previewWidget);
    }
  }, [widgetData, currentStep, setPreviewWidget, open]);

  useEffect(() => {
    if (!open) {
      setPreviewWidget(null);
      setWidgetData(null);
      setCurrentStep(isEditMode ? DialogStep.EDIT : DialogStep.ADD);
    }
  }, [open, setPreviewWidget, isEditMode]);

  const handleSelectWidget = (widgetType: string) => {
    const newData = createDefaultWidgetConfig(widgetType, widgetResolver);
    setWidgetData(newData);
    setCurrentStep(DialogStep.EDIT);
  };

  const handleBack = () => {
    if (currentStep === DialogStep.EDIT && !isEditMode) {
      setCurrentStep(DialogStep.ADD);
    }
  };

  const handleChange = (data: Partial<AddWidgetConfig>) => {
    setWidgetData((prev) => {
      if (!prev) return prev;
      const merged = { ...prev, ...data };
      return merged as AddWidgetConfig;
    });
  };

  const handleSave = async () => {
    if (!widgetData) return;
    const isValid = await editorRef.current?.submit();
    if (isValid) {
      onSave(widgetData);
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
          <WidgetConfigDialogAddStep onSelectWidget={handleSelectWidget} />
        )}

        {currentStep === DialogStep.EDIT && widgetData && (
          <WidgetConfigDialogEditStep
            widgetData={widgetData}
            onChange={handleChange}
            editorComponent={editorComponent}
            editorRef={editorRef}
          />
        )}

        <div className="flex justify-between gap-2 pt-4">
          <div>
            {currentStep === DialogStep.EDIT && !isEditMode && (
              <Button variant="outline" onClick={handleBack}>
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
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default WidgetConfigDialog;
