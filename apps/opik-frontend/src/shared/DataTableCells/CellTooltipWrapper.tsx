import React, { useCallback } from "react";
import { Slot } from "@radix-ui/react-slot";
import { useTablePopover } from "@/shared/DataTable/DataTableTooltipContext";
import { EMPTY_CELL_PLACEHOLDER } from "@/shared/DataTableCells/EmptyCellPlaceholder";

type CellTooltipWrapperProps = {
  content?: string;
  children: React.ReactNode;
};

const MIN_CHARACTERS_FOR_TOOLTIP = 20;

const CellTooltipWrapper: React.FC<CellTooltipWrapperProps> = ({
  content,
  children,
}) => {
  const { showPopover, hidePopover } = useTablePopover();
  const triggerRef = React.useRef<HTMLElement>(null);

  const handleMouseEnter = useCallback(() => {
    showPopover(content, triggerRef);
  }, [content, showPopover]);

  const showTooltip =
    content &&
    content !== EMPTY_CELL_PLACEHOLDER &&
    content.length > MIN_CHARACTERS_FOR_TOOLTIP;
  return showTooltip ? (
    <Slot
      ref={triggerRef}
      onMouseEnter={handleMouseEnter}
      onMouseLeave={hidePopover}
    >
      {children}
    </Slot>
  ) : (
    children
  );
};

export default CellTooltipWrapper;
