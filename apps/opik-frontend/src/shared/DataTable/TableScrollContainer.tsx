import React, { useState } from "react";
import noop from "lodash/noop";

import { PageBodyScrollContainerContext } from "@/contexts/usePageBodyScrollContainer";
import { cn } from "@/lib/utils";

type TableScrollContainerProps = {
  children: React.ReactNode;
  className?: string;
};

const TableScrollContainer: React.FC<TableScrollContainerProps> = ({
  children,
  className,
}) => {
  const [scrollContainer, setScrollContainer] = useState<HTMLDivElement | null>(
    null,
  );
  const [horizontalScrollContainer, setHorizontalScrollContainer] =
    useState<HTMLElement | null>(null);

  return (
    <PageBodyScrollContainerContext.Provider
      value={{
        scrollContainer,
        horizontalScrollContainer,
        registerHorizontalScrollContainer: setHorizontalScrollContainer,
        tableOffset: 0,
        recalculateOffsets: noop,
      }}
    >
      <div
        ref={setScrollContainer}
        className={cn(
          "min-h-0 flex-1 overflow-x-hidden overflow-y-auto",
          className,
        )}
      >
        {children}
      </div>
    </PageBodyScrollContainerContext.Provider>
  );
};

export default TableScrollContainer;
