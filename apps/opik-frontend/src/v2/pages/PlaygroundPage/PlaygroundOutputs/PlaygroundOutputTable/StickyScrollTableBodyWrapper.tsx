import React, { useEffect } from "react";

import usePageBodyScrollContainer from "@/contexts/usePageBodyScrollContainer";
import { TABLE_WRAPPER_ATTRIBUTE } from "@/v2/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";

const StickyScrollTableBodyWrapper: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => {
  const { recalculateOffsets } = usePageBodyScrollContainer();

  useEffect(() => {
    recalculateOffsets();
  }, [recalculateOffsets]);

  return (
    <div
      className="border-b [&_thead]:hidden"
      {...{ [TABLE_WRAPPER_ATTRIBUTE]: "" }}
    >
      {children}
    </div>
  );
};

export default StickyScrollTableBodyWrapper;
