import * as React from "react";
import * as CheckboxPrimitive from "@radix-ui/react-checkbox";
import { cva, type VariantProps } from "class-variance-authority";
import { Check, Minus } from "lucide-react";

import { cn } from "@/lib/utils";

const checkboxVariants = cva(
  "peer size-4 shrink-0 rounded-sm border ring-offset-background focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50",
  {
    variants: {
      variant: {
        default:
          "border-border focus-visible:border-primary-active data-[state=checked]:border-primary data-[state=indeterminate]:border-primary data-[state=checked]:bg-primary data-[state=indeterminate]:bg-primary data-[state=checked]:text-primary-foreground data-[state=indeterminate]:text-primary-foreground focus-visible:data-[state=checked]:border-primary-active focus-visible:data-[state=indeterminate]:border-primary-active focus-visible:data-[state=checked]:bg-primary-active focus-visible:data-[state=indeterminate]:bg-primary-active data-[state=checked]:dark:text-foreground data-[state=indeterminate]:dark:text-foreground",
        muted:
          "border-muted-slate focus-visible:border-primary-active data-[state=checked]:border-primary data-[state=indeterminate]:border-primary data-[state=checked]:bg-primary data-[state=indeterminate]:bg-primary data-[state=checked]:text-primary-foreground data-[state=indeterminate]:text-primary-foreground focus-visible:data-[state=checked]:border-primary-active focus-visible:data-[state=indeterminate]:border-primary-active focus-visible:data-[state=checked]:bg-primary-active focus-visible:data-[state=indeterminate]:bg-primary-active data-[state=checked]:dark:text-foreground data-[state=indeterminate]:dark:text-foreground",
      },
    },
    defaultVariants: {
      variant: "default",
    },
  },
);

type CheckboxProps = React.ComponentPropsWithoutRef<
  typeof CheckboxPrimitive.Root
> &
  VariantProps<typeof checkboxVariants>;

const Checkbox = React.forwardRef<
  React.ElementRef<typeof CheckboxPrimitive.Root>,
  CheckboxProps
>(({ className, checked, variant, ...props }, ref) => (
  <CheckboxPrimitive.Root
    ref={ref}
    className={cn(checkboxVariants({ variant }), className)}
    checked={checked}
    {...props}
  >
    <CheckboxPrimitive.Indicator
      className={cn("flex items-center justify-center text-current")}
    >
      {checked === "indeterminate" && <Minus className="size-3" />}
      {checked === true && <Check className="size-3" strokeWidth="3" />}
    </CheckboxPrimitive.Indicator>
  </CheckboxPrimitive.Root>
));
Checkbox.displayName = CheckboxPrimitive.Root.displayName;

export { Checkbox };
