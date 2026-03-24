import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "@/lib/utils";

const hotkeyDisplayVariants = cva(
  "inline-flex items-center justify-center rounded-md opacity-90",
  {
    variants: {
      variant: {
        default: "bg-[#FFFFFF33]",
        outline:
          "border border-input bg-background dark:border-border dark:bg-input dark:text-foreground-secondary dark:group-disabled:text-muted-gray",
      },
      size: {
        default: "h-8 min-w-8 px-2",
        "2xs": "h-[16px] min-w-4 rounded px-[2px] text-xs",
        xs: "h-[18px] min-w-4 rounded px-[3px] text-xs",
        sm: "h-6 min-w-6 rounded-md px-1.5",
        lg: "h-10 min-w-10 rounded-md px-3",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "sm",
    },
  },
);

export interface HotkeyDisplayProps
  extends React.HTMLAttributes<HTMLSpanElement>,
    VariantProps<typeof hotkeyDisplayVariants> {
  hotkey: string;
}

const HotkeyDisplay = React.forwardRef<HTMLSpanElement, HotkeyDisplayProps>(
  ({ className, variant, size, hotkey, ...props }, ref) => {
    return (
      <span
        className={cn(hotkeyDisplayVariants({ variant, size, className }))}
        ref={ref}
        {...props}
      >
        {hotkey}
      </span>
    );
  },
);
HotkeyDisplay.displayName = "HotkeyDisplay";

export { HotkeyDisplay, hotkeyDisplayVariants };
