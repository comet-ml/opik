import * as React from "react";
import { Slot } from "@radix-ui/react-slot";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

const listActionVariants = cva(
  "flex w-full cursor-pointer items-center gap-2 rounded transition-colors hover:bg-primary-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50",
  {
    variants: {
      variant: {
        primary: "comet-body-s-accented text-primary",
        default: "comet-body-s text-foreground",
      },
      size: {
        default: "h-10 px-4",
        sm: "h-8 px-3",
      },
    },
    defaultVariants: { variant: "primary", size: "default" },
  },
);

interface ListActionProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof listActionVariants> {
  asChild?: boolean;
}

const ListAction = React.forwardRef<HTMLButtonElement, ListActionProps>(
  ({ className, variant, size, asChild = false, ...props }, ref) => {
    const Comp = asChild ? Slot : "button";
    return (
      <Comp
        ref={ref}
        type={asChild ? undefined : "button"}
        className={cn(listActionVariants({ variant, size }), className)}
        {...props}
      />
    );
  },
);
ListAction.displayName = "ListAction";

export { ListAction, listActionVariants };
