import { LayoutGrid } from "lucide-react";
import OllieIcon from "@/icons/ollie-owl.svg?react";
import { MenuOption } from "@/shared/MenuOptionItems/MenuOptionItems";

export type AddWidgetOptionValue = "add" | "describe";

export const ADD_WIDGET_OPTIONS: MenuOption<AddWidgetOptionValue>[] = [
  {
    value: "add",
    label: "Add widget",
    description: "Pick a built-in chart and configure it.",
    icon: LayoutGrid,
    iconClassName: "bg-primary-100",
    iconColorClassName: "text-primary",
  },
  {
    value: "describe",
    label: "Describe a widget",
    description: "Tell Ollie what to show and it builds a custom chart.",
    icon: OllieIcon,
    iconClassName: "bg-[color-mix(in_srgb,var(--color-ollie)_12%,transparent)]",
    iconColorClassName: "text-[var(--color-ollie)]",
  },
];
