import React from "react";
import { cn } from "@/lib/utils";

export enum STICKY_DIRECTION {
  horizontal = "horizontal",
  vertical = "vertical",
  bidirectional = "bidirectional",
}

export const STICKY_ATTRIBUTE_NAME = "data-sticky-attribute";

type PageStickyScrollContainerProps = {
  children: React.ReactNode;
  className?: string;
  direction?: "horizontal" | "vertical" | "bidirectional";
  limitWidth?: boolean;
};

const PageBodyStickyContainer: React.FC<PageStickyScrollContainerProps> = ({
  children,
  className,
  direction = STICKY_DIRECTION.vertical,
  limitWidth = false,
}) => {
  return (
    <div
      {...{ [STICKY_ATTRIBUTE_NAME]: direction }}
      className={cn(
        "sticky z-10 bg-soft-background",
        direction === STICKY_DIRECTION.horizontal && "left-0",
        direction === STICKY_DIRECTION.vertical && "top-0",
        direction === STICKY_DIRECTION.bidirectional && "left-0 top-0",
        limitWidth && "w-[var(--scroll-body-client-padding-box)]",
        className,
      )}
    >
      {children}
    </div>
  );
};
export default PageBodyStickyContainer;
