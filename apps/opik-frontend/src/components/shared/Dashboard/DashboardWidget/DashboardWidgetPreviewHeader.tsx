import React from "react";
import { Info } from "lucide-react";
import {
  useDashboardStore,
  selectPreviewWidget,
  selectUpdatePreviewWidget,
} from "@/store/DashboardStore";
import InlineEditableText from "@/components/shared/InlineEditableText/InlineEditableText";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";

type DashboardWidgetPreviewHeaderProps = {
  className?: string;
  infoMessage?: string;
};

const DashboardWidgetPreviewHeader: React.FunctionComponent<
  DashboardWidgetPreviewHeaderProps
> = ({ className, infoMessage }) => {
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
    <div className={cn("flex flex-col gap-0.5 pt-1", className)}>
      <InlineEditableText
        value={displayTitle}
        placeholder={titlePlaceholder}
        defaultValue={generatedTitle}
        onChange={handleTitleChange}
        isTitle
        rightIcon={
          infoMessage ? (
            <TooltipWrapper content={infoMessage}>
              <Info className="size-3 shrink-0 text-light-slate" />
            </TooltipWrapper>
          ) : undefined
        }
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
