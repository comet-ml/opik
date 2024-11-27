import React from "react";
import { HeaderContext } from "@tanstack/react-table";
import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";
import HeaderWrapper from "@/components/shared/DataTableHeaders/HeaderWrapper";

const FeedbackScoreHeader = <TData,>(
  context: HeaderContext<TData, unknown>,
) => {
  const { column } = context;
  const { header } = column.columnDef.meta ?? {};
  const color = TAG_VARIANTS_COLOR_MAP[generateTagVariant(header!)!];

  return (
    <HeaderWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <div
        className="mr-0.5 size-2 shrink-0 rounded-[2px] bg-[--color-bg]"
        style={{ "--color-bg": color } as React.CSSProperties}
      ></div>
      <span className="truncate">{header}</span>
    </HeaderWrapper>
  );
};

export default FeedbackScoreHeader;
