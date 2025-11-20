import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "@/lib/utils";

const tagVariants = cva("inline-block truncate rounded-sm transition-colors", {
  variants: {
    variant: {
      default: "border border-border bg-background text-muted-slate",
      primary: "bg-primary-100 text-primary-hover",
      gray: "bg-[var(--tag-gray-bg)] text-muted-slate dark:text-foreground",
      purple: "bg-[var(--tag-purple-bg)] text-[var(--tag-purple-text)]",
      burgundy: "bg-[var(--tag-burgundy-bg)] text-[var(--tag-burgundy-text)]",
      pink: "bg-[var(--tag-pink-bg)] text-[var(--tag-pink-text)]",
      red: "bg-[var(--tag-red-bg)] text-[var(--tag-red-text)]",
      orange: "bg-[var(--tag-orange-bg)] text-[var(--tag-orange-text)]",
      yellow: "bg-[var(--tag-yellow-bg)] text-[var(--tag-yellow-text)]",
      green: "bg-[var(--tag-green-bg)] text-[var(--tag-green-text)]",
      turquoise:
        "bg-[var(--tag-turquoise-bg)] text-[var(--tag-turquoise-text)]",
      blue: "bg-[var(--tag-blue-bg)] text-[var(--tag-blue-text)]",
      transparent: "border border-border bg-transparent",
    },
    size: {
      default: "comet-body-xs-accented h-5 px-2 leading-5",
      sm: "comet-body-xs-accented h-4 px-2 text-[11px] leading-4",
      md: "comet-body-s-accented h-6 rounded-md px-1.5 leading-6",
      lg: "comet-body-s-accented h-7 rounded-md px-3 leading-7",
    },
  },
  defaultVariants: {
    variant: "default",
    size: "default",
  },
});

export interface TagProps
  extends React.HTMLAttributes<HTMLDivElement>,
    VariantProps<typeof tagVariants> {}

const Tag = React.forwardRef<HTMLDivElement, TagProps>(
  ({ className, variant, size, ...props }, ref) => {
    return (
      <div
        className={cn(tagVariants({ variant, size, className }))}
        ref={ref}
        {...props}
      />
    );
  },
);
Tag.displayName = "Tag";

export const TAG_VARIANTS: Exclude<
  TagProps["variant"],
  "red" | "transparent"
>[] = [
  "primary",
  "gray",
  "purple",
  "burgundy",
  "pink",
  "orange",
  "yellow",
  "green",
  "turquoise",
  "blue",
];

export const TAG_VARIANTS_COLOR_MAP: Record<
  Exclude<TagProps["variant"], null | undefined | "red" | "transparent">,
  string
> = {
  default: "var(--color-gray)",
  primary: "var(--color-primary)",
  gray: "var(--color-gray)",
  purple: "var(--color-purple)",
  burgundy: "var(--color-burgundy)",
  pink: "var(--color-pink)",
  orange: "var(--color-orange)",
  yellow: "var(--color-yellow)",
  green: "var(--color-green)",
  turquoise: "var(--color-turquoise)",
  blue: "var(--color-blue)",
};

export { Tag, tagVariants };
