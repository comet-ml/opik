import React, { useState, useEffect } from "react";
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
} from "@/types/dashboard";
import { DEFAULT_WIDGET_TITLES } from "../constants";

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

  const widgetResolver = useDashboardStore(selectWidgetResolver);
  const getWidgetById = useDashboardStore((state) => state.getWidgetById);
  const setPreviewWidget = useDashboardStore(selectSetPreviewWidget);

  const editorComponent = widgetData.type
    ? widgetResolver?.(widgetData.type)?.Editor || null
    : null;

  useEffect(() => {
    if (mode === "create") {
      setCurrentStep("add");
      setWidgetData({
        type: WIDGET_TYPE.TEXT_MARKDOWN,
        title: "",
        subtitle: "",
        config: {},
      });
    } else if (mode === "edit" && widgetId) {
      setCurrentStep("edit");
      const widget = getWidgetById(sectionId, widgetId);
      if (widget) {
        setWidgetData(convertWidgetToAddConfig(widget));
      }
    }
  }, [mode, open, widgetId, sectionId, getWidgetById]);

  useEffect(() => {
    if (currentStep === "edit") {
      const previewWidget: DashboardWidget = {
        id: "preview",
        title: widgetData.title || "Widget title",
        subtitle: widgetData.subtitle,
        type: widgetData.type,
        config: widgetData.config || {},
      };
      setPreviewWidget(previewWidget);
    }
  }, [widgetData, currentStep, setPreviewWidget]);

  useEffect(() => {
    if (!open) {
      setPreviewWidget(null);
    }
  }, [open, setPreviewWidget]);

  const handleSelectWidget = (widgetType: string) => {
    const newData = {
      type: widgetType as WIDGET_TYPE,
      title: DEFAULT_WIDGET_TITLES[widgetType] || "Widget",
      subtitle: "",
      config: {},
    } as AddWidgetConfig;
    setWidgetData(newData);
  };

  const handleNext = () => {
    if (currentStep === "add") {
      setCurrentStep("edit");
    }
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

  const handleSave = () => {
    onSave(widgetData);
    onOpenChange(false);
  };

  const handleCancel = () => {
    onOpenChange(false);
  };

  const dialogTitle = mode === "create" ? "Add widget" : "Edit widget";
  const dialogDescription =
    currentStep === "add"
      ? "Choose a widget type to add to your dashboard"
      : "Configure your widget settings and preview";

  const canProceed = currentStep === "add" ? widgetData.type !== null : true;

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
            {currentStep === "add" ? (
              <Button onClick={handleNext} disabled={!canProceed}>
                Next
              </Button>
            ) : (
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
