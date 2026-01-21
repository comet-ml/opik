import React from "react";
import { Trace } from "@/types/traces";
import { CustomViewSchema, WidgetSize } from "@/types/custom-view";
import { resolveTracePath } from "@/lib/tracePathResolver";
import WidgetRenderer from "@/components/pages/CustomViewDemoPage/widgets/WidgetRenderer";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

interface AnnotationCustomViewPanelProps {
  trace: Trace;
  viewSchema: CustomViewSchema;
}

const AnnotationCustomViewPanel: React.FC<AnnotationCustomViewPanelProps> = ({
  trace,
  viewSchema,
}) => {
  // Helper function to get column span classes based on widget size
  const getColumnSpanClass = (size: WidgetSize): string => {
    switch (size) {
      case WidgetSize.SMALL:
        return "col-span-6 md:col-span-3 xl:col-span-2";
      case WidgetSize.MEDIUM:
        return "col-span-6 md:col-span-6 xl:col-span-3";
      case WidgetSize.LARGE:
        return "col-span-6 xl:col-span-4";
      case WidgetSize.FULL:
        return "col-span-6";
    }
  };

  if (!viewSchema.widgets || viewSchema.widgets.length === 0) {
    return (
      <div className="flex items-center justify-center py-12 text-center">
        <div>
          <div className="comet-body-s mb-2 text-muted-slate">
            No Widgets Available
          </div>
          <div className="comet-body-xs text-muted-slate">
            The saved view doesn&apos;t contain any widgets.
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Widgets grid */}
      <div className="grid grid-cols-6 gap-6">
        {viewSchema.widgets.map((widget, index) => {
          const value = resolveTracePath(trace, widget.path);
          const columnSpanClass = getColumnSpanClass(widget.size);

          return (
            <TooltipWrapper key={index} content={`Path: ${widget.path}`}>
              <div className={columnSpanClass}>
                <WidgetRenderer
                  type={widget.uiWidget}
                  value={value}
                  label={widget.label}
                  path={widget.path}
                />
              </div>
            </TooltipWrapper>
          );
        })}
      </div>
    </div>
  );
};

export default AnnotationCustomViewPanel;
