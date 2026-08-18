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
      ochre: "bg-[var(--tag-ochre-bg)] text-[var(--tag-ochre-text)]",
      lavender: "bg-[var(--tag-lavender-bg)] text-[var(--tag-lavender-text)]",
      white:
        "border border-gray-200 bg-white text-muted-slate dark:border-gray-600 dark:bg-gray-800 dark:text-foreground",
      transparent: "border border-border bg-transparent",
    },
    size: {
      default: "comet-body-xs h-5 px-2 leading-5",
      sm: "comet-body-xs h-4 px-2 text-[11px] leading-4",
      md: "comet-body-s h-6 px-2 leading-6",
      lg: "comet-body-s h-7 rounded-md px-3 leading-7",
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

/**
 * Tag label typography scale (distinct from the layout `size` variant):
 * "s" → comet-body-s (14px), "xs" → comet-body-xs (12px, for compact headers).
 */
export type TagTextSize = "s" | "xs";

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

/**
 * Palette used to derive a color automatically from a label (see `generateTagVariant`).
 *
 * Two invariants are enforced by `lib/colorVariants.test.ts` and must hold for any change here:
 * - every pair of entries stays perceptually distinguishable (OPIK-7839: `primary` #6366f1 sat at
 *   ΔE 14.5 from `purple`, which made distinct series look identical in grouped charts);
 * - the list keeps its length, because the automatic color is `hash % TAG_VARIANTS.length` —
 *   adding or removing an entry re-maps every label in the product, while substituting one
 *   re-maps only the labels that resolved to that slot.
 *
 * `primary` is deliberately absent: it is too close to both `purple` and `blue` for chart series.
 */
export const TAG_VARIANTS: Exclude<
  TagProps["variant"],
  "red" | "transparent" | "white" | "lavender"
>[] = [
  "ochre",
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
  Exclude<
    TagProps["variant"],
    null | undefined | "red" | "transparent" | "white" | "lavender"
  >,
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
  ochre: "var(--color-ochre)",
};

export { Tag, tagVariants };
