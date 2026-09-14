import React, { useCallback, useState } from "react";
import noop from "lodash/noop";

import { PageBodyScrollContainerContext } from "@/contexts/usePageBodyScrollContainer";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import { TABLE_WRAPPER_ATTRIBUTE } from "@/v2/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";

// Distance from the scroller's content top to the table, measured through bounding
// rects rather than offsetTop so the scroller does not need its own positioning context.
const calculateTableOffset = (node: HTMLDivElement) => {
  const tableWrapper = node.querySelector(`[${TABLE_WRAPPER_ATTRIBUTE}]`);

  if (!(tableWrapper instanceof HTMLElement)) {
    return 0;
  }

  return (
    tableWrapper.getBoundingClientRect().top -
    node.getBoundingClientRect().top +
    node.scrollTop
  );
};

type PlaygroundScrollContainerProps = {
  children: React.ReactNode;
  className?: string;
  style?: React.CSSProperties;
};

const PlaygroundScrollContainer: React.FC<PlaygroundScrollContainerProps> = ({
  children,
  className,
  style,
}) => {
  const [tableOffset, setTableOffset] = useState(0);

  const handleResize = useCallback((node: HTMLDivElement) => {
    setTableOffset(calculateTableOffset(node));
  }, []);

  const { ref, node } = useObserveResizeNode<HTMLDivElement>(handleResize);

  const recalculateOffsets = useCallback(() => {
    if (node) {
      setTableOffset(calculateTableOffset(node));
    }
  }, [node]);

  return (
    <PageBodyScrollContainerContext.Provider
      value={{
        scrollContainer: node ?? null,
        horizontalScrollContainer: null,
        registerHorizontalScrollContainer: noop,
        tableOffset,
        recalculateOffsets,
      }}
    >
      <div ref={ref} className={className} style={style}>
        {children}
      </div>
    </PageBodyScrollContainerContext.Provider>
  );
};

export default PlaygroundScrollContainer;
