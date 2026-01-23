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
  generatedSubtitle?: string;
};

const DashboardWidgetPreviewHeader: React.FunctionComponent<
  DashboardWidgetPreviewHeaderProps
> = ({ className, generatedSubtitle }) => {
  const previewWidget = useDashboardStore(selectPreviewWidget);
  const updatePreviewWidget = useDashboardStore(selectUpdatePreviewWidget);

  if (!previewWidget) {
    return null;
  }

  const { title, subtitle, generatedTitle } = previewWidget;
  const displayTitle = title || generatedTitle || "";
  const displaySubtitle = subtitle || generatedSubtitle || "";
  const titlePlaceholder = generatedTitle || "Enter widget title";
  const subtitlePlaceholder = generatedSubtitle || "Click to add a description";

  const handleTitleChange = (newTitle: string) => {
    updatePreviewWidget({ title: newTitle });
  };

  const handleSubtitleChange = (newSubtitle: string) => {
    updatePreviewWidget({ subtitle: newSubtitle });
  };

  return (
    <div className={cn("flex flex-col gap-0.5 pt-1", className)}>
      <InlineEditableText
        value={displayTitle}
        placeholder={titlePlaceholder}
        defaultValue={generatedTitle}
        onChange={handleTitleChange}
        isTitle
      />
      <InlineEditableText
        value={displaySubtitle}
        placeholder={subtitlePlaceholder}
        defaultValue={generatedSubtitle}
        onChange={handleSubtitleChange}
      />
    </div>
  );
};

export default DashboardWidgetPreviewHeader;
