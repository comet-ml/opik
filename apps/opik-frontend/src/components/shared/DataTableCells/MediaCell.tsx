import React from "react";
import { CellContext } from "@tanstack/react-table";
import isString from "lodash/isString";
import { ROW_HEIGHT } from "@/types/shared";
import { useMediaTypeDetection } from "@/hooks/useMediaTypeDetection";
import { isParsedMediaData } from "@/lib/images";
import ImagesListWrapper from "@/components/shared/attachments/ImagesListWrapper/ImagesListWrapper";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import CellTooltipWrapper from "@/components/shared/DataTableCells/CellTooltipWrapper";

const MediaCell = <TData,>(context: CellContext<TData, unknown>) => {
  const value = context.getValue();

  // Use only the user's row height setting, not overrideRowHeight
  // overrideRowHeight is for layout purposes only (e.g., split cells in experiment comparison)
  // The user's row height selection should determine whether images or links are displayed
  const rowHeight = context.table.options.meta?.rowHeight ?? ROW_HEIGHT.small;

  const isBig = rowHeight === ROW_HEIGHT.large;

  const alreadyParsed = isParsedMediaData(value);

  const { mediaData: detectedMedia } = useMediaTypeDetection(
    value,
    isBig && !alreadyParsed,
  );

  const mediaData = alreadyParsed ? value : detectedMedia;

  const getContent = () => {
    const displayValue = isString(value) ? value : mediaData?.url || "-";

    if (!isBig) {
      return (
        <CellTooltipWrapper content={displayValue}>
          <span className="truncate">{displayValue}</span>
        </CellTooltipWrapper>
      );
    }

    if (mediaData) {
      return (
        <div className="max-h-80 max-w-[320px] overflow-y-auto">
          <ImagesListWrapper media={[mediaData]} />
        </div>
      );
    }

    return (
      <div className="size-full overflow-y-auto whitespace-pre-wrap break-words">
        {displayValue}
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
