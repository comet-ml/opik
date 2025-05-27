import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "@/lib/utils";

const alertVariants = cva(
  "relative w-full rounded-lg [&>svg]:absolute [&>svg]:text-muted-slate",
  {
    variants: {
      variant: {
        default: "bg-muted text-foreground-secondary",
        callout: "border bg-primary-foreground",
        destructive:
          "border border-destructive/50 text-destructive dark:border-destructive [&>svg]:text-destructive",
      },
      size: {
        md: "comet-body-s p-4 [&>svg]:left-[18px] [&>svg]:top-[18px] [&>svg]:size-4 [&>svg~*]:pl-7",
        sm: "comet-body-xs p-3 [&>svg]:left-[13px] [&>svg]:top-[13px] [&>svg]:size-3.5 [&>svg~*]:pl-[22px]",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "md",
    },
  },
);

const Alert = React.forwardRef<
  HTMLDivElement,
  React.HTMLAttributes<HTMLDivElement> & VariantProps<typeof alertVariants>
>(({ className, variant, size, ...props }, ref) => (
  <div
    ref={ref}
    role="alert"
    className={cn(alertVariants({ variant, size }), className)}
    {...props}
  />
));
Alert.displayName = "Alert";

const alertTitleVariants = cva("font-medium text-foreground-secondary", {
  variants: {
    size: {
      md: "mb-1 h-4 leading-5",
      sm: "mb-0.5 h-3.5 leading-4",
    },
  },
  defaultVariants: {
    size: "md",
  },
});

const AlertTitle = React.forwardRef<
  HTMLParagraphElement,
  React.HTMLAttributes<HTMLHeadingElement> &
    VariantProps<typeof alertTitleVariants>
>(({ className, size, ...props }, ref) => (
  <h5
    ref={ref}
    className={cn(alertTitleVariants({ size }), className)}
    {...props}
  />
));
AlertTitle.displayName = "AlertTitle";

const alertDescriptionVariants = cva("text-muted-slate [&_p]:leading-relaxed", {
  variants: {
    size: {
      md: "leading-5",
      sm: "leading-4",
    },
  },
  defaultVariants: {
    size: "md",
  },
});

const AlertDescription = React.forwardRef<
  HTMLParagraphElement,
  React.HTMLAttributes<HTMLParagraphElement> &
    VariantProps<typeof alertDescriptionVariants>
>(({ className, size, ...props }, ref) => (
  <div
    ref={ref}
    className={cn(alertDescriptionVariants({ size }), className)}
    {...props}
  />
));
AlertDescription.displayName = "AlertDescription";

export { Alert, AlertTitle, AlertDescription };
