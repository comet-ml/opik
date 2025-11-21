import React from "react";
import { CellContext } from "@tanstack/react-table";
import { ROW_HEIGHT } from "@/types/shared";
import { parseImageValue, parseVideoValue } from "@/lib/images";
import ImagesListWrapper from "@/components/pages-shared/attachments/ImagesListWrapper/ImagesListWrapper";
import { ATTACHMENT_TYPE } from "@/types/attachments";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import CellTooltipWrapper from "@/components/shared/DataTableCells/CellTooltipWrapper";

const MediaCell = <TData,>(context: CellContext<TData, unknown>) => {
  const value = context.getValue() as string;

  const rowHeight =
    context.column.columnDef.meta?.overrideRowHeight ??
    context.table.options.meta?.rowHeight ??
    ROW_HEIGHT.small;

  const isBig = rowHeight === ROW_HEIGHT.large;

  const video = parseVideoValue(value);
  const image = !video ? parseImageValue(value) : undefined;

  const getContent = () => {
    if (!isBig) {
      return (
        <CellTooltipWrapper content={value}>
          <span className="truncate">{value}</span>
        </CellTooltipWrapper>
      );
    }

    if (video) {
      return (
        <div className="max-h-80 max-w-[320px] overflow-y-auto">
          <ImagesListWrapper
            media={[{ ...video, type: ATTACHMENT_TYPE.VIDEO }]}
          />
        </div>
      );
    }

    if (image) {
      return (
        <div className="max-h-80 max-w-[320px] overflow-y-auto">
          <ImagesListWrapper
            media={[{ ...image, type: ATTACHMENT_TYPE.IMAGE }]}
          />
        </div>
      );
    }

    return (
      <div className="size-full overflow-y-auto whitespace-pre-wrap break-words">
        {value}
      </div>
    );
  };

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {getContent()}
    </CellWrapper>
  );
};

export default MediaCell;
