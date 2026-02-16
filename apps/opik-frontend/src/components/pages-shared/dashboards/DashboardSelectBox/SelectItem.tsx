import React from "react";
import {
  ChartLine,
  Check,
  Copy,
  MoreVertical,
  Pencil,
  Trash,
} from "lucide-react";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { Dashboard } from "@/types/dashboard";

interface SelectItemProps {
  id: string;
  name: string;
  description?: string;
  icon?: React.ComponentType<{ className?: string }>;
  iconColor?: string;
  isSelected: boolean;
  onSelect: (id: string) => void;
  // Optional actions for user dashboards
  dashboard?: Dashboard;
  onEdit?: (dashboard: Dashboard) => void;
  onDuplicate?: (dashboard: Dashboard) => void;
  onDelete?: (dashboard: Dashboard) => void;
}

export const SelectItem: React.FC<SelectItemProps> = ({
  id,
  name,
  description,
  icon,
  iconColor,
  isSelected,
  onSelect,
  dashboard,
  onEdit,
  onDuplicate,
  onDelete,
}) => {
  const Icon = icon || ChartLine;
  const hasActions = dashboard && onEdit && onDuplicate && onDelete;

  // Use DropdownMenuItem for templates (better keyboard navigation)
  if (!hasActions) {
    return (
      <DropdownMenuItem
        className={cn(
          "min-h-12 cursor-pointer items-start gap-2 py-2 pl-8",
          isSelected && "bg-primary-foreground",
        )}
        onSelect={() => onSelect(id)}
      >
        {isSelected && (
          <Check
            className="absolute left-2 top-2.5 size-3.5 text-muted-slate"
            strokeWidth="3"
          />
        )}
        <Icon
          className={cn(
            "mt-0.5 size-4 shrink-0",
            iconColor ? iconColor : "text-muted-slate",
          )}
        />
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <div className="comet-body-s truncate text-foreground">{name}</div>
          {description && (
            <TooltipWrapper content={description}>
              <div className="comet-body-xs line-clamp-2 text-muted-foreground">
                {description}
              </div>
            </TooltipWrapper>
          )}
        </div>
      </DropdownMenuItem>
    );
  }

  // Use div for dashboards (to support nested dropdown menu)
  return (
    <div
      className={cn(
        "group relative flex min-h-12 cursor-pointer items-start gap-2 rounded-md py-2 pl-8 pr-4 hover:bg-primary-foreground",
        isSelected && "bg-primary-foreground",
      )}
      onClick={() => onSelect(id)}
    >
      {isSelected && (
        <Check
          className="absolute left-2 top-2.5 size-3.5 text-muted-slate"
          strokeWidth="3"
        />
      )}
      <Icon
        className={cn(
          "mt-0.5 size-4 shrink-0",
          iconColor ? iconColor : "text-muted-slate",
        )}
      />
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <div className="comet-body-s truncate text-foreground">{name}</div>
        {description && (
          <TooltipWrapper content={description}>
            <div className="comet-body-xs line-clamp-2 text-muted-foreground">
              {description}
            </div>
          </TooltipWrapper>
        )}
      </div>
      {dashboard && (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              variant="ghost"
              size="icon-xs"
              className="mt-0.5 text-muted-slate opacity-0 group-hover:opacity-100"
              onClick={(e) => e.stopPropagation()}
            >
              <MoreVertical className="size-4" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent
            align="end"
            side="right"
            onClick={(e) => e.stopPropagation()}
          >
            <DropdownMenuItem
              onClick={(e) => {
                e.stopPropagation();
                onEdit?.(dashboard);
              }}
              className="hover:text-primary"
            >
              <Pencil className="mr-2 size-4" />
              Edit
            </DropdownMenuItem>
            <DropdownMenuItem
              onClick={(e) => {
                e.stopPropagation();
                onDuplicate?.(dashboard);
              }}
            >
              <Copy className="mr-2 size-4" />
              Duplicate
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem
              onClick={(e) => {
                e.stopPropagation();
                onDelete?.(dashboard);
              }}
              variant="destructive"
            >
              <Trash className="mr-2 size-4" />
              Delete
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      )}
    </div>
  );
};
