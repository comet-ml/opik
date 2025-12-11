import React from "react";
import { BarChart3 } from "lucide-react";

import { cn } from "@/lib/utils";
import { TEMPLATE_LIST, DASHBOARD_TEMPLATES } from "@/lib/dashboard/templates";
import { isTemplateId } from "@/lib/dashboard/utils";
import { TEMPLATE_TYPE, Dashboard } from "@/types/dashboard";

interface TriggerContentProps {
  selectedItem: (typeof TEMPLATE_LIST)[number] | Dashboard | null | undefined;
  value: string | null;
}

export const TriggerContent: React.FC<TriggerContentProps> = ({
  selectedItem,
  value,
}) => {
  if (!selectedItem) {
    return (
      <div className="flex w-full items-center text-light-slate">
        <BarChart3 className="mr-2 size-4" />
        <span className="truncate font-normal">Select a dashboard</span>
      </div>
    );
  }

  const isTemplate = isTemplateId(value);

  if (isTemplate) {
    const templateType = value?.replace("template:", "") as TEMPLATE_TYPE;
    const template = DASHBOARD_TEMPLATES[templateType];
    if (template) {
      const TemplateIcon = template.icon;
      return (
        <div className="flex w-full items-center text-foreground">
          <TemplateIcon
            className={cn("mr-2 size-4 shrink-0", template.iconColor)}
          />
          <span className="max-w-[90%] truncate">
            {"title" in selectedItem ? selectedItem.title : selectedItem.name}
          </span>
        </div>
      );
    }
  }

  return (
    <div className="flex w-full items-center text-foreground">
      <BarChart3 className="mr-2 size-4 shrink-0 text-muted-slate" />
      <span className="max-w-[90%] truncate">
        {"name" in selectedItem ? selectedItem.name : selectedItem.title}
      </span>
    </div>
  );
};
