import React, { useCallback } from "react";
import { Slot } from "@radix-ui/react-slot";
import { useTablePopover } from "@/components/shared/DataTable/DataTableTooltipContext";

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
    content && content !== "-" && content.length > MIN_CHARACTERS_FOR_TOOLTIP;
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
