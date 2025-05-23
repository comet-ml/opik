import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "@/lib/utils";

const tagVariants = cva(
  "comet-body-xs-accented inline-block truncate rounded-sm transition-colors",
  {
    variants: {
      variant: {
        default: "border border-border bg-background text-muted-slate",
        primary: "bg-primary-100 text-primary-hover",
        gray: "bg-[#F3F4F6] text-muted-slate",
        purple: "bg-[#EFE2FD] text-[#491B7E]",
        burgundy: "bg-[#FDE2F6] text-[#72275F]",
        pink: "bg-[#FDE2EA] text-[#822C45]",
        red: "bg-[#FDC9C9] text-[#4E1D1D]",
        orange: "bg-[#FEE3D7] text-[#73422B]",
        yellow: "bg-[#FEF0C8] text-[#675523]",
        green: "bg-[#DAFBF0] text-[#295747]",
        turquoise: "bg-[#CDF5F9] text-[#15545B]",
        blue: "bg-[#E2EFFD] text-[#19426B]",
      },
      size: {
        default: "h-5 px-2 leading-5",
        sm: "h-4 px-2 text-[11px] leading-4",
        md: "h-6 rounded-md px-1.5 leading-6",
        lg: "comet-body-s-accented h-7 rounded-md px-3 leading-7",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  },
);

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

export const TAG_VARIANTS: TagProps["variant"][] = [
  "primary",
  "gray",
  "purple",
  "burgundy",
  "pink",
  "red",
  "orange",
  "yellow",
  "green",
  "turquoise",
  "blue",
];

export const TAG_VARIANTS_COLOR_MAP: Record<
  Exclude<TagProps["variant"], null | undefined>,
  string
> = {
  default: "#64748B",
  primary: "#5155F5",
  gray: "#64748B",
  purple: "#945FCF",
  burgundy: "#BF399E",
  pink: "#ED4A7B",
  red: "#EF6868",
  orange: "#FB9341",
  yellow: "#F4B400",
  green: "#19A979",
  turquoise: "#12A4B4",
  blue: "#5899DA",
};

export { Tag, tagVariants };
