import React from "react";

import { cn } from "@/lib/utils";

type IconTagSize = "sm" | "md";

const SIZE_CLASSES: Record<IconTagSize, { tag: string; icon: string }> = {
  sm: { tag: "size-4 rounded-[4px]", icon: "size-2.5" },
  md: { tag: "h-5 rounded-md px-1", icon: "size-3" },
};

type IconTagProps = {
  icon: React.ComponentType<{ className?: string }>;
  className: string;
  iconClassName?: string;
  size?: IconTagSize;
};

const IconTag: React.FC<IconTagProps> = ({
  icon: Icon,
  className,
  iconClassName = "text-foreground dark:text-background",
  size = "sm",
}) => (
  <span
    className={cn(
      "flex shrink-0 items-center justify-center",
      SIZE_CLASSES[size].tag,
      className,
    )}
  >
    <Icon className={cn(iconClassName, SIZE_CLASSES[size].icon)} />
  </span>
);

export default IconTag;
