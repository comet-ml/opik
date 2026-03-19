import React from "react";
import { HeaderContext } from "@tanstack/react-table";
import HeaderWrapper from "@/components/shared/DataTableHeaders/HeaderWrapper";
import { PLAYGROUND_PROMPT_COLORS } from "@/constants/llm";

const PlaygroundOutputColumnHeader = <TData,>(
  context: HeaderContext<TData, unknown>,
) => {
  const { column } = context;
  const { header, custom } = column.columnDef.meta ?? {};
  const promptIndex = (custom as { promptIndex?: number })?.promptIndex ?? 0;
  const promptColor =
    PLAYGROUND_PROMPT_COLORS[promptIndex % PLAYGROUND_PROMPT_COLORS.length];

  return (
    <HeaderWrapper>
      <div className="flex items-center gap-1.5">
        <span
          className="inline-block size-3 rounded-sm"
          style={{ backgroundColor: promptColor.bg }}
        />
        <span className="truncate">{header}</span>
      </div>
    </HeaderWrapper>
  );
};

export default PlaygroundOutputColumnHeader;
