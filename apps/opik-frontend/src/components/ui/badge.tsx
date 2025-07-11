import * as React from "react";
import { Tag, TagProps, tagVariants } from "./tag";

export interface BadgeProps extends TagProps {}

const Badge = React.forwardRef<HTMLDivElement, BadgeProps>(
  ({ className, variant, size, ...props }, ref) => {
    return (
      <Tag
        className={className}
        variant={variant}
        size={size}
        ref={ref}
        {...props}
      />
    );
  },
);
Badge.displayName = "Badge";

export { Badge, tagVariants as badgeVariants }; 