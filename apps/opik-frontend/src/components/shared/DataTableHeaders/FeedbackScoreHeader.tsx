import React from "react";
import { HeaderContext } from "@tanstack/react-table";
import ColorIndicator from "@/components/shared/ColorIndicator/ColorIndicator";
import HeaderWrapper from "@/components/shared/DataTableHeaders/HeaderWrapper";
import useSortableHeader from "@/components/shared/DataTableHeaders/useSortableHeader";
import { FeedbackScoreCustomMeta } from "@/types/feedback-scores";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { formatScoreDisplay } from "@/lib/feedback-scores";
import useWorkspaceColorMap from "@/hooks/useWorkspaceColorMap";

const FeedbackScoreHeader = <TData,>(
  context: HeaderContext<TData, unknown>,
) => {
  const { column } = context;
  const { header, custom } = column.columnDef.meta ?? {};
  const { colorMap, scoreName, prefixIcon, scoreValue } = (custom ??
    {}) as FeedbackScoreCustomMeta;

  const { getColor } = useWorkspaceColorMap();
  const effectiveColorKey = scoreName ?? header ?? "";
  const color = getColor(effectiveColorKey, colorMap);

  const { className, onClickHandler, renderSort } = useSortableHeader({
    column,
  });

  const formattedScore =
    scoreValue !== undefined ? formatScoreDisplay(scoreValue) : null;

  return (
    <HeaderWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className={className}
      onClick={onClickHandler}
    >
      {prefixIcon}
      <ColorIndicator
        label={scoreName ?? header ?? ""}
        color={color}
        variant="square"
        className="mr-0.5 shrink-0"
      />
      {header ? (
        <TooltipWrapper content={header} side="top">
          <span className="truncate">{header}</span>
        </TooltipWrapper>
      ) : (
        <span className="truncate"></span>
      )}
      {formattedScore !== null && (
        <span className="shrink-0 text-foreground">{formattedScore}</span>
      )}
      {renderSort()}
    </HeaderWrapper>
  );
};

export default FeedbackScoreHeader;
