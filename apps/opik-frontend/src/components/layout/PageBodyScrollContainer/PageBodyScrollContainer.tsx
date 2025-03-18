import React, { useState } from "react";

import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import { cn } from "@/lib/utils";
import { STICKY_ATTRIBUTE_VERTICAL } from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";

type PageBodyScrollContainerProps = {
  children: React.ReactNode;
  className?: string;
};

const PageBodyScrollContainer: React.FC<PageBodyScrollContainerProps> = ({
  children,
  className,
}) => {
  const [width, setWidth] = useState<number>(0);

  const { ref } = useObserveResizeNode<HTMLDivElement>((node) => {
    setWidth(node.clientWidth);

    const verticalElements = node.querySelectorAll(
      `[${STICKY_ATTRIBUTE_VERTICAL}]`,
    );
    let offset = 0;
    verticalElements.forEach((element) => {
      element.setAttribute("style", `top: ${offset}px`);
      offset += element.clientHeight;
    });
  });

  const style =
    width > 0
      ? ({
          "--scroll-body-client-width": `${width}px`,
        } as React.CSSProperties)
      : undefined;

  return (
    <div
      ref={ref}
      style={style}
      className={cn(
        "relative h-[calc(100vh-var(--header-height))] overflow-auto -mx-6",
        className,
      )}
    >
      {children}
    </div>
  );
};

export default PageBodyScrollContainer;
