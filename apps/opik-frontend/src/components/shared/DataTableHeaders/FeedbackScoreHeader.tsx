import React from "react";
import { HeaderContext } from "@tanstack/react-table";
import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";
import HeaderWrapper from "@/components/shared/DataTableHeaders/HeaderWrapper";
import useSortableHeader from "@/components/shared/DataTableHeaders/useSortableHeader";

const FeedbackScoreHeader = <TData,>(
  context: HeaderContext<TData, unknown>,
) => {
  const { column } = context;
  const { header } = column.columnDef.meta ?? {};
  const color = TAG_VARIANTS_COLOR_MAP[generateTagVariant(header ?? "")!];

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
      <span className="truncate">{header}</span>
      {renderSort()}
    </HeaderWrapper>
  );
};

export default FeedbackScoreHeader;
