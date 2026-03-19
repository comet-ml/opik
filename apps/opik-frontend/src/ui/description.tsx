import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "@/lib/utils";

const descriptionVariants = cva("comet-body-s text-light-slate");

export interface DescriptionProps
  extends React.HTMLAttributes<HTMLSpanElement>,
    VariantProps<typeof descriptionVariants> {
  asChild?: boolean;
}

const Description = React.forwardRef<HTMLSpanElement, DescriptionProps>(
  ({ className, ...props }, ref) => (
    <span
      ref={ref}
      className={cn(descriptionVariants(), className)}
      {...props}
    />
  ),
);
Description.displayName = "Description";

export { Description };
