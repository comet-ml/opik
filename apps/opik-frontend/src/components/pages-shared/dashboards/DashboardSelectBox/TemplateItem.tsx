import React from "react";
import { Check } from "lucide-react";

import { DropdownMenuItem } from "@/components/ui/dropdown-menu";
import { cn } from "@/lib/utils";
import { TEMPLATE_LIST } from "@/lib/dashboard/templates";

interface TemplateItemProps {
  template: (typeof TEMPLATE_LIST)[number];
  isSelected: boolean;
  onSelect: (id: string) => void;
}

export const TemplateItem: React.FC<TemplateItemProps> = ({
  template,
  isSelected,
  onSelect,
}) => {
  const TemplateIcon = template.icon;

  return (
    <DropdownMenuItem
      className={cn(
        "min-h-12 cursor-pointer gap-2 py-2",
        isSelected && "bg-primary-foreground",
      )}
      onSelect={() => onSelect(template.id)}
    >
      <div className="flex min-w-4 items-center justify-center">
        {isSelected && <Check className="size-3.5 shrink-0" strokeWidth="3" />}
      </div>
      <TemplateIcon className={cn("size-4 shrink-0", template.iconColor)} />
      <div className="flex min-w-0 flex-1 flex-col">
        <div className="comet-body-s truncate">{template.title}</div>
        <div className="comet-body-xs truncate text-muted-foreground">
          {template.description}
        </div>
      </div>
    </DropdownMenuItem>
  );
};
