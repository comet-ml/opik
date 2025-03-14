import React, { useState } from "react";

import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import { cn } from "@/lib/utils";

type PageBodyScrollContainerProps = {
  children: React.ReactNode;
  className?: string;
};

const PageBodyScrollContainer: React.FC<PageBodyScrollContainerProps> = ({
  children,
  className,
}) => {
  const [style, setStyle] = useState<React.CSSProperties>({});

  const { ref } = useObserveResizeNode<HTMLDivElement>((node) => {
    const computedStyle = getComputedStyle(node);
    setStyle({
      "--scroll-body-client-width": `${node.clientWidth}px`,
      "--scroll-body-client-padding-box": `${
        node.clientWidth -
        parseInt(computedStyle.paddingLeft, 10) -
        parseInt(computedStyle.paddingRight, 10)
      }px`,
    } as React.CSSProperties);
  });

  return (
    <div
      ref={ref}
      style={style}
      className={cn(
        "relative h-[calc(100vh-var(--header-height))] overflow-auto -mx-6 px-6",
        className,
      )}
    >
      {children}
    </div>
  );
};

export default PageBodyScrollContainer;
