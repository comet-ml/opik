import React from "react";
import { FlaskConical, LayoutGridIcon, LucideIcon } from "lucide-react";

import { DASHBOARD_TYPE, DASHBOARD_TYPE_LABELS } from "@/types/dashboard";
import { DropdownOption } from "@/types/shared";
import { SelectItem } from "@/components/ui/select";
import { TagProps } from "@/components/ui/tag";

export const DASHBOARD_TYPE_OPTIONS: DropdownOption<string>[] = Object.entries(
  DASHBOARD_TYPE_LABELS,
).map(([value, label]) => ({ value, label }));

export const DASHBOARD_TYPE_ICON_MAP: Record<
  string,
  { icon: LucideIcon; className: string }
> = {
  [DASHBOARD_TYPE.MULTI_PROJECT]: {
    icon: LayoutGridIcon,
    className: "text-chart-red",
  },
  [DASHBOARD_TYPE.EXPERIMENTS]: {
    icon: FlaskConical,
    className: "text-chart-green",
  },
};

export const DASHBOARD_TYPE_TAG_VARIANT: Record<string, TagProps["variant"]> = {
  [DASHBOARD_TYPE_LABELS[DASHBOARD_TYPE.MULTI_PROJECT]]: "pink",
  [DASHBOARD_TYPE_LABELS[DASHBOARD_TYPE.EXPERIMENTS]]: "green",
};

export const renderDashboardTypeOption = (option: DropdownOption<string>) => {
  const config = DASHBOARD_TYPE_ICON_MAP[option.value];
  return (
    <SelectItem
      key={option.value}
      value={option.value}
      withoutCheck
      wrapperAsChild={true}
    >
      <div className="flex w-full items-center gap-2">
        {config && (
          <config.icon className={`size-4 shrink-0 ${config.className}`} />
        )}
        {option.label}
      </div>
    </SelectItem>
  );
};
