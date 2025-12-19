import React from "react";
import {
  useDashboardStore,
  selectPreviewWidget,
  selectUpdatePreviewWidget,
} from "@/store/DashboardStore";
import InlineEditableText from "@/components/shared/InlineEditableText/InlineEditableText";
import { cn } from "@/lib/utils";

type DashboardWidgetPreviewHeaderProps = {
  className?: string;
};

const DashboardWidgetPreviewHeader: React.FunctionComponent<
  DashboardWidgetPreviewHeaderProps
> = ({ className }) => {
  const previewWidget = useDashboardStore(selectPreviewWidget);
  const updatePreviewWidget = useDashboardStore(selectUpdatePreviewWidget);

  if (!previewWidget) {
    return null;
  }

  const { title, subtitle, generatedTitle } = previewWidget;
  const displayTitle = title || generatedTitle || "";
  const titlePlaceholder = generatedTitle || "Enter widget title";

  const handleTitleChange = (newTitle: string) => {
    updatePreviewWidget({ title: newTitle });
  };

  const handleSubtitleChange = (newSubtitle: string) => {
    updatePreviewWidget({ subtitle: newSubtitle });
  };

  return (
    <div className={cn("flex flex-col pt-1", className)}>
      <InlineEditableText
        value={displayTitle}
        placeholder={titlePlaceholder}
        defaultValue={generatedTitle}
        onChange={handleTitleChange}
        isTitle
      />
      <InlineEditableText
        value={subtitle || ""}
        placeholder="Click to add a description"
        defaultValue=""
        onChange={handleSubtitleChange}
      />
    </div>
  );
};

export default DashboardWidgetPreviewHeader;
