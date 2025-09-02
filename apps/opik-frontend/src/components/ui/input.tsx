import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "@/lib/utils";

const inputVariants = cva(
  "flex w-full rounded-md bg-background text-sm text-foreground file:border-0 file:bg-transparent file:text-sm file:font-medium placeholder:text-light-slate focus-visible:outline-none disabled:cursor-not-allowed disabled:bg-muted-disabled disabled:text-muted-gray disabled:placeholder:text-muted-gray",
  {
    variants: {
      variant: {
        default:
          "border border-border invalid:border-warning hover:shadow-sm focus-visible:border-primary focus-visible:invalid:border-warning hover:disabled:shadow-none",
        ghost: "",
      },
      dimension: {
        default: "h-10 px-3 py-2",
        sm: "h-8 px-3 pb-1.5 pt-1",
      },
    },
    defaultVariants: {
      variant: "default",
      dimension: "default",
    },
  },
);

export interface InputProps
  extends React.InputHTMLAttributes<HTMLInputElement>,
    VariantProps<typeof inputVariants> {}

const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({ className, variant, dimension, type, ...props }, ref) => {
    return (
      <input
        type={type}
        className={cn(inputVariants({ variant, dimension, className }))}
        ref={ref}
        {...props}
      />
    );
  },
);
Input.displayName = "Input";

export { Input };
