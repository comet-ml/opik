import React from "react";
import { cn } from "@/lib/utils";

export enum STICKY_DIRECTION {
  horizontal = "horizontal",
  vertical = "vertical",
  bidirectional = "bidirectional",
}

export const STICKY_ATTRIBUTE_VERTICAL = "data-sticky-vertical";

type PageBodyStickyContainerProps = {
  children?: React.ReactNode;
  className?: string;
  direction?: "horizontal" | "vertical" | "bidirectional";
  limitWidth?: boolean;
};

const PageBodyStickyContainer: React.FC<PageBodyStickyContainerProps> = ({
  children,
  className,
  direction = STICKY_DIRECTION.vertical,
  limitWidth = false,
}) => {
  return (
    <div
      {...((direction === STICKY_DIRECTION.vertical ||
        direction === STICKY_DIRECTION.bidirectional) && {
        [STICKY_ATTRIBUTE_VERTICAL]: direction,
      })}
      className={cn(
        "sticky z-10 bg-soft-background px-6",
        direction === STICKY_DIRECTION.horizontal && "left-0",
        direction === STICKY_DIRECTION.vertical && "top-0",
        direction === STICKY_DIRECTION.bidirectional && "left-0 top-0",
        limitWidth &&
          "w-[var(--scroll-body-client-width,var(--comet-content-width))]",
        className,
      )}
    >
      {children}
    </div>
  );
};
export default PageBodyStickyContainer;
