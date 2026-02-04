import React from "react";
import isString from "lodash/isString";
import { useMediaTypeDetection } from "@/hooks/useMediaTypeDetection";
import ImagesListWrapper from "@/components/shared/attachments/ImagesListWrapper/ImagesListWrapper";
import CellTooltipWrapper from "@/components/shared/DataTableCells/CellTooltipWrapper";

interface TextAndMediaContentRendererProps {
  value: unknown;
  isBig: boolean;
}

const TextAndMediaContentRenderer: React.FC<
  TextAndMediaContentRendererProps
> = ({ value, isBig }) => {
  const { mediaData } = useMediaTypeDetection(value, isBig);

  const displayValue = isString(value) ? value : "-";

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

export default TextAndMediaContentRenderer;
