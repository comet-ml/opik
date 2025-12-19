import React, { memo } from "react";
import { useShallow } from "zustand/react/shallow";

import DashboardWidget from "@/components/shared/Dashboard/DashboardWidget/DashboardWidget";
import { useDashboardStore } from "@/store/DashboardStore";
import MarkdownPreview from "@/components/shared/MarkdownPreview/MarkdownPreview";
import { DashboardWidgetComponentProps } from "@/types/dashboard";

const TextMarkdownWidget: React.FunctionComponent<
  DashboardWidgetComponentProps
> = ({ sectionId, widgetId, preview = false }) => {
  const widget = useDashboardStore(
    useShallow((state) => {
      if (preview) {
        return state.previewWidget;
      }
      if (!sectionId || !widgetId) return null;
      const section = state.sections.find((s) => s.id === sectionId);
      return section?.widgets.find((w) => w.id === widgetId);
    }),
  );

  if (!widget) {
    return null;
  }

  const renderContent = () => {
    const content = widget?.config?.content as string | undefined;

    if (!content || content.trim() === "") {
      return (
        <DashboardWidget.EmptyState
          title="No content"
          message="Click edit to add markdown content to this widget"
        />
      );
    }

    return (
      <div className="h-full overflow-auto p-4">
        <MarkdownPreview>{content}</MarkdownPreview>
      </div>
    );
  };

  return (
    <DashboardWidget>
      {preview ? (
        <DashboardWidget.PreviewHeader />
      ) : (
        <DashboardWidget.Header
          title={widget.title || widget.generatedTitle || ""}
          subtitle={widget.subtitle}
          actions={
            <DashboardWidget.ActionsMenu
              sectionId={sectionId!}
              widgetId={widgetId!}
              widgetTitle={widget.title}
            />
          }
          dragHandle={<DashboardWidget.DragHandle />}
        />
      )}
      <DashboardWidget.Content>{renderContent()}</DashboardWidget.Content>
    </DashboardWidget>
  );
};

const arePropsEqual = (
  prev: DashboardWidgetComponentProps,
  next: DashboardWidgetComponentProps,
) => {
  if (prev.preview !== next.preview) return false;
  if (prev.preview && next.preview) return true;
  return prev.sectionId === next.sectionId && prev.widgetId === next.widgetId;
};

export default memo(TextMarkdownWidget, arePropsEqual);
