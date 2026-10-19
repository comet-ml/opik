import React, { useEffect, useRef } from "react";
import { ArrowRight } from "lucide-react";
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
import OllieIcon from "@/icons/ollie-owl.svg?react";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import usePluginsStore from "@/store/PluginsStore";
import { FeatureToggleKeys } from "@/types/feature-toggles";
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

  // The sidebar plugin is what answers the bridge; without it the request would go nowhere.
  const assistantAvailable = usePluginsStore((state) =>
    Boolean(state.AssistantSidebar),
  );
  // Ollie can only build a widget in a workspace on the Custom Charts allowlist.
  const customChartsEnabledForWorkspace = useIsFeatureEnabled(
    FeatureToggleKeys.CUSTOM_CHARTS_ENABLED_FOR_WORKSPACE,
  );
  const ollieEnabled =
    useIsFeatureEnabled(FeatureToggleKeys.OLLIE_ENABLED) &&
    customChartsEnabledForWorkspace &&
    assistantAvailable;
  // Hands the request to Ollie, which draws it and offers "Add to <this dashboard>" on the chart.
  const handleDescribeToOllie = () => {
    onOpenChange(false);
    window.opikBridge?.startConversation(
      "I want to add a custom widget to this dashboard. Ask me what it should show.",
    );
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

        <DialogFooter className="flex flex-row items-center justify-end gap-2 border-t pt-4 sm:flex-row sm:justify-end">
          {!isEditMode && ollieEnabled && (
            <div className="comet-code mr-auto flex items-center gap-2 text-foreground">
              Need a custom widget?
              <button
                type="button"
                onClick={handleDescribeToOllie}
                className="inline-flex items-center gap-1 border-b border-current pb-0.5 hover:text-primary-hover"
              >
                <OllieIcon className="size-4 text-[var(--color-ollie)]" />
                Ask Ollie
                <ArrowRight className="size-4" />
              </button>
            </div>
          )}
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
