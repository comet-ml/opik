import React from "react";
import { AddWidgetConfig } from "@/types/dashboard";
import {
  useDashboardStore,
  selectWidgetResolver,
  selectPreviewWidget,
} from "@/store/DashboardStore";

interface WidgetConfigPreviewProps {
  widgetData: AddWidgetConfig;
}

const WidgetConfigPreview: React.FunctionComponent<
  WidgetConfigPreviewProps
> = ({ widgetData }) => {
  const widgetResolver = useDashboardStore(selectWidgetResolver);
  const previewWidget = useDashboardStore(selectPreviewWidget);

  const WidgetComponent = widgetResolver?.(widgetData.type)?.Widget;

  if (!WidgetComponent || !previewWidget) {
    return (
      <div className="flex h-full items-center justify-center rounded-md border border-border bg-white p-4">
        <p className="text-center text-2xl font-black text-slate-300">
          &lt;preview&gt;
        </p>
      </div>
    );
  }

  return (
    <div className="h-full overflow-hidden rounded-md border border-border bg-white">
      <WidgetComponent preview />
    </div>
  );
};

export default WidgetConfigPreview;
