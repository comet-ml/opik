import React, { useState } from "react";
import { cn } from "@/lib/utils";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import { STICKY_ATTRIBUTE_VERTICAL } from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import { PageBodyScrollContainerContext } from "@/components/layout/PageBodyScrollContainer/usePageBodyScrollContainer";
import { TABLE_WRAPPER_ATTRIBUTE } from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";

type PageBodyScrollContainerProps = {
  children: React.ReactNode;
  className?: string;
};

const calculateOffsets = (node: HTMLDivElement) => {
  const tableWrapper = node.querySelector(`[${TABLE_WRAPPER_ATTRIBUTE}]`);
  let tableOffset = 0;
  if (tableWrapper instanceof HTMLElement) {
    tableOffset = tableWrapper.offsetTop;
  }
  const verticalElements = node.querySelectorAll(
    `[${STICKY_ATTRIBUTE_VERTICAL}]`,
  );
  let offset = 0;
  verticalElements.forEach((element) => {
    element.setAttribute("style", `top: ${offset}px`);
    offset += element.clientHeight;
  });

  return { width: node.clientWidth, tableOffset: tableOffset };
};

const PageBodyScrollContainer: React.FC<PageBodyScrollContainerProps> = ({
  children,
  className,
}) => {
  const [width, setWidth] = useState<number>(0);
  const [tableOffset, setTableOffset] = useState<number>(0);
  const { ref, node: scrollContainer } = useObserveResizeNode<HTMLDivElement>(
    (node) => {
      const { width, tableOffset } = calculateOffsets(node);
      setWidth(width);
      setTableOffset(tableOffset);
    },
  );
  const style =
    width > 0
      ? ({ "--scroll-body-client-width": `${width}px` } as React.CSSProperties)
      : undefined;

  return (
    <PageBodyScrollContainerContext.Provider
      value={{ scrollContainer: scrollContainer ?? null, tableOffset }}
    >
      <div
        ref={ref}
        style={style}
        className={cn(
          "relative h-[calc(100vh-var(--header-height)-var(--banner-height))] overflow-auto -mx-6",
          className,
        )}
      >
        {children}
      </div>
    </PageBodyScrollContainerContext.Provider>
  );
};
export default PageBodyScrollContainer;
