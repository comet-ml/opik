import React, { useEffect } from "react";
import usePageBodyScrollContainer from "@/contexts/usePageBodyScrollContainer";

export const TABLE_WRAPPER_ATTRIBUTE = "data-table-wrapper";

type PageBodyStickyTableWrapperProps = {
  children: React.ReactNode;
};

const PageBodyStickyTableWrapper: React.FC<PageBodyStickyTableWrapperProps> = ({
  children,
}) => {
  const { recalculateOffsets } = usePageBodyScrollContainer();

  useEffect(() => {
    recalculateOffsets();
  }, [recalculateOffsets]);

  return (
    <div
      // min-w-fit ensures border-b extends the full table width when scrolling horizontally
      className="comet-sticky-table min-w-fit border-b"
      {...{
        [TABLE_WRAPPER_ATTRIBUTE]: "",
      }}
    >
      {children}
    </div>
  );
};
export default PageBodyStickyTableWrapper;
