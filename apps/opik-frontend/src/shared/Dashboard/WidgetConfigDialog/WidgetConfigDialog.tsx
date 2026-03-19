import React, { useEffect, useRef } from "react";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
import {
  useDashboardStore,
  selectWidgetResolver,
  selectSetPreviewWidget,
  selectRuntimeConfig,
} from "@/store/DashboardStore";
import WidgetTypeSelector from "./WidgetTypeSelector";
import WidgetConfigPreview from "./WidgetConfigPreview";
import {
  DashboardWidget,
  WidgetConfigDialogProps,
  WidgetEditorHandle,
} from "@/types/dashboard";
import {
  createDefaultWidgetConfig,
  getWidgetTypesForDashboard,
} from "@/lib/dashboard/utils";

const WidgetConfigDialog: React.FunctionComponent<WidgetConfigDialogProps> = ({
  open,
  onOpenChange,
  sectionId,
  widgetId,
  onSave,
}) => {
  const isEditMode = !!widgetId;
  const editorRef = useRef<WidgetEditorHandle>(null);

  const widgetResolver = useDashboardStore(selectWidgetResolver);
  const getWidgetById = useDashboardStore((state) => state.getWidgetById);
  const previewWidget = useDashboardStore((state) => state.previewWidget);
  const setPreviewWidget = useDashboardStore(selectSetPreviewWidget);
  const runtimeConfig = useDashboardStore(selectRuntimeConfig);

  const EditorComponent =
    previewWidget?.type && widgetResolver
      ? widgetResolver(previewWidget.type)?.Editor || null
      : null;

  const metadata =
    previewWidget?.type && widgetResolver
      ? widgetResolver(previewWidget.type)?.metadata
      : null;

  useEffect(() => {
    if (!open) return;

    if (isEditMode && widgetId) {
      const widget = getWidgetById(sectionId, widgetId);
      if (widget) {
        setPreviewWidget(widget);
      }
    } else {
      const widgetTypes = getWidgetTypesForDashboard(
        runtimeConfig.dashboardType,
      );
      if (widgetTypes.length > 0 && widgetResolver) {
        const firstType = widgetTypes[0];
        const newWidget = createDefaultWidgetConfig(firstType, widgetResolver);
        setPreviewWidget({ ...newWidget, id: "preview" } as DashboardWidget);
      }
    }
  }, [
    isEditMode,
    open,
    widgetId,
    sectionId,
    getWidgetById,
    setPreviewWidget,
    widgetResolver,
    runtimeConfig.dashboardType,
  ]);

  useEffect(() => {
    if (!open) {
      setPreviewWidget(null);
    }
  }, [open, setPreviewWidget]);

  const handleSelectWidget = (widgetType: string) => {
    const newWidget = createDefaultWidgetConfig(widgetType, widgetResolver);
    setPreviewWidget({ ...newWidget, id: "preview" } as DashboardWidget);
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

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-6xl">
        <DialogHeader>
          <DialogTitle>{dialogTitle}</DialogTitle>
        </DialogHeader>

        <div className="grid h-[65vh] max-h-[800px] grid-cols-[1fr_460px] gap-4">
          <div className="overflow-y-auto">
            <div className="space-y-4 pb-2">
              {!isEditMode && (
                <WidgetTypeSelector
                  selectedType={previewWidget?.type}
                  onSelect={handleSelectWidget}
                />
              )}
              {previewWidget?.type && metadata && (
                <>
                  <Separator />
                  <div>
                    <h3 className="comet-body-accented text-foreground">
                      {metadata.title}
                    </h3>
                    {metadata.description && (
                      <p className="comet-body-s text-light-slate">
                        {metadata.description}
                      </p>
                    )}
                  </div>
                  {EditorComponent && <EditorComponent ref={editorRef} />}
                </>
              )}
            </div>
          </div>
          <div className="flex h-full min-h-0 flex-col gap-3 overflow-hidden">
            <WidgetConfigPreview />
          </div>
        </div>

        <DialogFooter className="flex flex-row justify-end gap-2 border-t pt-4 sm:flex-row sm:justify-end">
          <Button variant="outline" onClick={handleCancel}>
            Cancel
          </Button>
          <Button onClick={handleSave} disabled={!previewWidget}>
            {isEditMode ? "Save changes" : "Add widget"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default WidgetConfigDialog;
