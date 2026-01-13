import React from "react";
import {
  useDashboardStore,
  selectWidgetResolver,
  selectPreviewWidget,
} from "@/store/DashboardStore";

const WidgetConfigPreview: React.FunctionComponent = () => {
  const widgetResolver = useDashboardStore(selectWidgetResolver);
  const previewWidget = useDashboardStore(selectPreviewWidget);

  const WidgetComponent = previewWidget
    ? widgetResolver?.(previewWidget.type)?.Widget
    : null;

  if (!WidgetComponent || !previewWidget) {
    return (
      <div className="flex h-full min-h-0 flex-col gap-2">
        <p className="comet-body-s-accented pl-0.5">Widget preview</p>
        <div className="flex flex-1 items-center justify-center rounded-md border bg-white p-4">
          <p className="text-center text-2xl font-black text-slate-300">
            &lt;preview&gt;
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-2">
      <p className="comet-body-s-accented pl-0.5">Widget preview</p>
      <WidgetComponent preview />
    </div>
  );
};

export default WidgetConfigPreview;
