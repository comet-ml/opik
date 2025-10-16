import * as React from "react";
import * as TabsPrimitive from "@radix-ui/react-tabs";

import { cn } from "@/lib/utils";
import { cva, VariantProps } from "class-variance-authority";

const Tabs = TabsPrimitive.Root;

const TabsListVariants = cva(
  " inline-flex w-full items-center overflow-x-auto",
  {
    variants: {
      variant: {
        default: "gap-2 rounded-lg bg-muted p-1",
        underline: "gap-8 border-b px-1",
      },
    },
    defaultVariants: {
      variant: "default",
    },
  },
);

const TabsTriggerVariants = cva(
  "inline-flex items-center whitespace-nowrap transition-all disabled:pointer-events-none",
  {
    variants: {
      variant: {
        default:
          "comet-body-s-accented justify-center rounded-md bg-transparent p-2 ring-offset-background hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 data-[state=active]:bg-primary data-[state=active]:text-primary-foreground data-[state=active]:hover:bg-primary-hover data-[state=active]:hover:text-primary-foreground data-[state=active]:dark:text-primary-active",
        underline:
          "comet-body-s-accented border-b-2 border-transparent py-2 hover:text-foreground disabled:opacity-50 data-[state=active]:border-primary data-[state=active]:text-primary hover:data-[state=active]:text-primary-hover data-[state=active]:dark:border-primary-active data-[state=active]:dark:text-primary-active",
      },
      size: {
        default: "",
        sm: " text-xs",
        lg: "",
        icon: "size-9",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  },
);

export interface TabsListProps
  extends React.ComponentPropsWithoutRef<typeof TabsPrimitive.List>,
    VariantProps<typeof TabsListVariants> {
  asChild?: boolean;
}

const TabsList = React.forwardRef<
  React.ElementRef<typeof TabsPrimitive.List>,
  TabsListProps
>(({ className, variant, ...props }, ref) => (
  <TabsPrimitive.List
    ref={ref}
    className={cn(TabsListVariants({ variant, className }))}
    {...props}
  />
));
TabsList.displayName = TabsPrimitive.List.displayName;

export interface TabsTriggerProps
  extends React.ComponentPropsWithoutRef<typeof TabsPrimitive.Trigger>,
    VariantProps<typeof TabsTriggerVariants> {
  asChild?: boolean;
}

const TabsTrigger = React.forwardRef<
  React.ElementRef<typeof TabsPrimitive.Trigger>,
  TabsTriggerProps
>(({ className, variant, size, ...props }, ref) => (
  <TabsPrimitive.Trigger
    ref={ref}
    className={cn(TabsTriggerVariants({ variant, size, className }))}
    {...props}
  />
));
TabsTrigger.displayName = TabsPrimitive.Trigger.displayName;

const TabsContent = React.forwardRef<
  React.ElementRef<typeof TabsPrimitive.Content>,
  React.ComponentPropsWithoutRef<typeof TabsPrimitive.Content>
>(({ className, ...props }, ref) => (
  <TabsPrimitive.Content
    ref={ref}
    className={cn(
      "mt-4 ring-offset-background focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring",
      className,
    )}
    {...props}
  />
));
TabsContent.displayName = TabsPrimitive.Content.displayName;

export { Tabs, TabsList, TabsTrigger, TabsContent };
