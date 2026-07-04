import React, { useEffect } from "react";
import usePageBodyScrollContainer from "@/contexts/usePageBodyScrollContainer";
import { cn } from "@/lib/utils";

export const TABLE_WRAPPER_ATTRIBUTE = "data-table-wrapper";

type PageBodyStickyTableWrapperProps = {
  children: React.ReactNode;
  /**
   * Border classes for the wrapper. Defaults to a bottom border only (the
   * full-bleed table look); pass e.g. "rounded-md border" for a boxed table.
   */
  className?: string;
};

const PageBodyStickyTableWrapper: React.FC<PageBodyStickyTableWrapperProps> = ({
  children,
  className = "border-b",
}) => {
  const { recalculateOffsets } = usePageBodyScrollContainer();

  useEffect(() => {
    recalculateOffsets();
  }, [recalculateOffsets]);

  return (
    <div
      // min-w-fit ensures the border extends the full table width when scrolling horizontally
      className={cn("comet-sticky-table min-w-fit", className)}
      {...{
        [TABLE_WRAPPER_ATTRIBUTE]: "",
      }}
    >
      {children}
    </div>
  );
};
export default PageBodyStickyTableWrapper;
