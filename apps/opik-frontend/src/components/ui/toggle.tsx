import * as React from "react";
import * as TogglePrimitive from "@radix-ui/react-toggle";
import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "@/lib/utils";

const toggleVariants = cva(
  "comet-body-s inline-flex items-center justify-center rounded-sm ring-offset-background transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50",
  {
    variants: {
      variant: {
        default:
          "bg-transparent hover:bg-muted hover:text-foreground-secondary active:bg-primary-100 data-[state=on]:bg-primary data-[state=on]:font-medium data-[state=on]:text-primary-foreground hover:data-[state=on]:bg-primary-hover active:data-[state=on]:bg-primary-active",
        outline:
          "rounded-md border border-input bg-transparent hover:bg-accent hover:text-accent-foreground active:bg-[var(--toggle-outline-active)] data-[state=on]:bg-[var(--toggle-outline-active)]",
        ghost:
          "bg-transparent font-normal hover:text-primary-hover active:text-primary-active disabled:text-muted-gray disabled:opacity-100 data-[state=on]:bg-upload-icon-bg/40 data-[state=on]:text-foreground",
      },
      size: {
        default: "h-8 px-4",
        sm: "h-6 px-2",
        md: "h-7 px-2",
        lg: "h-9 px-5",
        icon: "size-8 p-2",
        "icon-sm": "size-6 p-1 [&>svg]:size-3 [&>svg]:shrink-0",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  },
);

const Toggle = React.forwardRef<
  React.ElementRef<typeof TogglePrimitive.Root>,
  React.ComponentPropsWithoutRef<typeof TogglePrimitive.Root> &
    VariantProps<typeof toggleVariants>
>(({ className, variant, size, ...props }, ref) => (
  <TogglePrimitive.Root
    ref={ref}
    className={cn(toggleVariants({ variant, size, className }))}
    {...props}
  />
));

Toggle.displayName = TogglePrimitive.Root.displayName;

export { Toggle, toggleVariants };
