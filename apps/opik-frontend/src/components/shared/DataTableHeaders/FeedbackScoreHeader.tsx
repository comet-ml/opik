import React from "react";
import { HeaderContext } from "@tanstack/react-table";
import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";

const FeedbackScoreHeader = <TData,>({
  column,
}: HeaderContext<TData, unknown>) => {
  const { header } = column.columnDef.meta ?? {};
  const color = TAG_VARIANTS_COLOR_MAP[generateTagVariant(header!)!];

  return (
    <div className="flex size-full items-center justify-end gap-1.5 px-3">
      <div
        className="size-2 shrink-0 rounded-[2px] bg-[--color-bg]"
        style={{ "--color-bg": color } as React.CSSProperties}
      ></div>
      <span className="truncate">{header}</span>
    </div>
  );
};

export default FeedbackScoreHeader;
