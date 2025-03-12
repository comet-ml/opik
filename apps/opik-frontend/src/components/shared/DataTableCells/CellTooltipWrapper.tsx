import React from "react";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

type CellTooltipWrapperProps = {
  content?: string;
  children: React.ReactNode;
};

const MIN_CHARACTERS_FOR_TOOLTIP = 20;

const CellTooltipWrapper: React.FC<CellTooltipWrapperProps> = ({
  content,
  children,
}) => {
  const showTooltip =
    content && content !== "-" && content.length > MIN_CHARACTERS_FOR_TOOLTIP;
  return showTooltip ? (
    <TooltipWrapper content={content}>{children}</TooltipWrapper>
  ) : (
    children
  );
};

export default CellTooltipWrapper;
