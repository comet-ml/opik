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
    setStyle({
      "--scroll-body-client-width": `${node.clientWidth}px`,
    } as React.CSSProperties);
  });

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
