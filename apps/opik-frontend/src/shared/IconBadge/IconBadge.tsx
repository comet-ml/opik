import React from "react";

import { cn } from "@/lib/utils";

export type IconBadgeColor =
  | "blue"
  | "pink"
  | "green"
  | "yellow"
  | "turquoise"
  | "purple"
  | "orange"
  | "gray";

const COLOR_CLASSES: Record<IconBadgeColor, string> = {
  blue: "bg-[var(--tag-blue-text)]",
  pink: "bg-[var(--tag-pink-text)]",
  green: "bg-[var(--tag-green-text)]",
  yellow: "bg-[var(--tag-yellow-text)]",
  turquoise: "bg-[var(--tag-turquoise-text)]",
  purple: "bg-[var(--tag-purple-text)]",
  orange: "bg-[var(--tag-orange-text)]",
  gray: "bg-muted-slate",
};

type IconBadgeProps = {
  Icon: React.ComponentType<{ className?: string }>;
  color?: IconBadgeColor;
  className?: string;
};

/** Solid 16px square with a white glyph, for labelling cards, chips and menu items. */
const IconBadge: React.FC<IconBadgeProps> = ({
  Icon,
  color = "gray",
  className,
}) => (
  <span
    className={cn(
      "flex size-4 shrink-0 items-center justify-center rounded-sm text-white",
      COLOR_CLASSES[color],
      className,
    )}
  >
    <Icon className="size-2.5" />
  </span>
);

export default IconBadge;
