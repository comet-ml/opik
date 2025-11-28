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
  const mode = widgetId ? "edit" : "create";
  const [currentStep, setCurrentStep] = useState<"add" | "edit">(
    mode === "create" ? "add" : "edit",
  );
  const [widgetData, setWidgetData] = useState<AddWidgetConfig>({
    type: WIDGET_TYPE.TEXT_MARKDOWN,
    title: "",
    subtitle: "",
    config: {},
  });
  const editorRef = useRef<WidgetEditorHandle>(null);

  const widgetResolver = useDashboardStore(selectWidgetResolver);
  const getWidgetById = useDashboardStore((state) => state.getWidgetById);
  const setPreviewWidget = useDashboardStore(selectSetPreviewWidget);

  const editorComponent = widgetData.type
    ? widgetResolver?.(widgetData.type)?.Editor || null
    : null;

  useEffect(() => {
    if (mode === "create" && open) {
      setCurrentStep("add");
    } else if (mode === "edit" && widgetId) {
      setCurrentStep("edit");
      const widget = getWidgetById(sectionId, widgetId);
      if (widget) {
        setWidgetData(convertWidgetToAddConfig(widget));
      }
    }
  }, [mode, open, widgetId, sectionId, getWidgetById]);

  useEffect(() => {
    if (open && currentStep === "edit") {
      const previewWidget: DashboardWidget = {
        id: "preview",
        title: widgetData.title || "Widget title",
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
    }
  }, [open, setPreviewWidget]);

  const handleSelectWidget = (widgetType: string) => {
    const newData = createDefaultWidgetConfig(widgetType, widgetResolver);
    setWidgetData(newData);
    setCurrentStep("edit");
  };

  const handleBack = () => {
    if (currentStep === "edit" && mode === "create") {
      setCurrentStep("add");
    }
  };

  const handleChange = (data: Partial<AddWidgetConfig>) => {
    setWidgetData((prev) => {
      const merged = { ...prev, ...data };
      return merged as AddWidgetConfig;
    });
  };

  const handleSave = async () => {
    const isValid = await editorRef.current?.submit();
    if (isValid) {
      onSave(widgetData);
      onOpenChange(false);
    }
  };

  const handleCancel = () => {
    onOpenChange(false);
  };

  const dialogTitle = mode === "create" ? "Add widget" : "Edit widget";
  const dialogDescription =
    currentStep === "add"
      ? "Choose a widget type to add to your dashboard"
      : mode === "create"
        ? "Adjust the data, visualization, or settings for this widget."
        : "Adjust the data, visualization, or settings for this widget. Changes will update the dashboard automatically.";

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-6xl">
        <DialogHeader>
          <DialogTitle>{dialogTitle}</DialogTitle>
          <DialogDescription>{dialogDescription}</DialogDescription>
        </DialogHeader>

        {currentStep === "add" && (
          <WidgetConfigDialogAddStep
            selectedWidgetType={widgetData.type}
            onSelectWidget={handleSelectWidget}
          />
        )}

        {currentStep === "edit" && (
          <WidgetConfigDialogEditStep
            widgetData={widgetData}
            onChange={handleChange}
            editorComponent={editorComponent}
            editorRef={editorRef}
          />
        )}

        <div className="flex justify-between gap-2 pt-4">
          <div>
            {currentStep === "edit" && mode === "create" && (
              <Button variant="outline" onClick={handleBack}>
                Back
              </Button>
            )}
          </div>
          <div className="flex gap-2">
            <Button variant="outline" onClick={handleCancel}>
              Cancel
            </Button>
            {currentStep === "edit" && (
              <Button onClick={handleSave}>
                {mode === "create" ? "Add widget" : "Save changes"}
              </Button>
            )}
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default WidgetConfigDialog;
