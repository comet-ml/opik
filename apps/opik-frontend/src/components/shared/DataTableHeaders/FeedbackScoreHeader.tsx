import React from "react";
import { HeaderContext } from "@tanstack/react-table";
import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";
import HeaderWrapper from "@/components/shared/DataTableHeaders/HeaderWrapper";
import useSortableHeader from "@/components/shared/DataTableHeaders/useSortableHeader";
import { FeedbackScoreCustomMeta } from "@/types/feedback-scores";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

const FeedbackScoreHeader = <TData,>(
  context: HeaderContext<TData, unknown>,
) => {
  const { column } = context;
  const { header, custom } = column.columnDef.meta ?? {};
  const { colorMap, feedbackKey } = (custom ?? {}) as FeedbackScoreCustomMeta;

  // Use color from colorMap if available, otherwise fall back to default
  const color =
    feedbackKey && colorMap?.[feedbackKey]
      ? colorMap[feedbackKey]
      : TAG_VARIANTS_COLOR_MAP[generateTagVariant(header ?? "")!];

  const { className, onClickHandler, renderSort } = useSortableHeader({
    column,
  });

  return (
    <HeaderWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className={className}
      onClick={onClickHandler}
    >
      <div
        className="mr-0.5 size-2 shrink-0 rounded-[2px] bg-[--color-bg]"
        style={{ "--color-bg": color } as React.CSSProperties}
      ></div>
      {header ? (
        <TooltipWrapper content={header} side="top">
          <span className="truncate">{header}</span>
        </TooltipWrapper>
      ) : (
        <span className="truncate"></span>
      )}
      {renderSort()}
    </HeaderWrapper>
  );
};

export default FeedbackScoreHeader;
