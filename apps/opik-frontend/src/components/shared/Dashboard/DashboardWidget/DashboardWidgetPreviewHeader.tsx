import React from "react";
import {
  useDashboardStore,
  selectPreviewWidget,
  selectUpdatePreviewWidget,
} from "@/store/DashboardStore";
import InlineEditableText from "@/components/shared/InlineEditableText/InlineEditableText";
import { cn } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

type DashboardWidgetPreviewHeaderProps = {
  className?: string;
  messages?: (string | React.ReactNode)[];
};

const DashboardWidgetPreviewHeader: React.FunctionComponent<
  DashboardWidgetPreviewHeaderProps
> = ({ className, messages }) => {
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

  const renderMessages = () => {
    if (!messages || messages.length === 0) return null;

    return messages.map((msg, index) => (
      <React.Fragment key={index}>
        {index > 0 && <span className="mx-1">·</span>}
        {msg}
      </React.Fragment>
    ));
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
        value={subtitle || ""}
        placeholder="Click to add a description"
        defaultValue=""
        onChange={handleSubtitleChange}
      />
      {messages && messages.length > 0 && (
        <TooltipWrapper content={<div>{renderMessages()}</div>}>
          <div className="line-clamp-2 px-2 text-xs font-normal text-muted-slate">
            {renderMessages()}
          </div>
        </TooltipWrapper>
      )}
    </div>
  );
};

export default DashboardWidgetPreviewHeader;
