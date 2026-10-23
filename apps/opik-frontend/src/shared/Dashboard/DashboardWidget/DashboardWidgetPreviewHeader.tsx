import React from "react";
import { Info } from "lucide-react";
import {
  useDashboardStore,
  selectPreviewWidget,
  selectUpdatePreviewWidget,
} from "@/store/DashboardStore";
import InlineEditableText from "@/shared/InlineEditableText/InlineEditableText";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
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
      <div className="flex items-center gap-1.5">
        <InlineEditableText
          value={displayTitle}
          placeholder={titlePlaceholder}
          defaultValue={generatedTitle}
          onChange={handleTitleChange}
          isTitle
          className="min-w-0 flex-1"
        />
        {infoMessage && (
          <TooltipWrapper content={infoMessage}>
            <button
              type="button"
              aria-label="Widget information"
              className="shrink-0"
            >
              <Info className="size-3 text-light-slate" />
            </button>
          </TooltipWrapper>
        )}
      </div>
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
