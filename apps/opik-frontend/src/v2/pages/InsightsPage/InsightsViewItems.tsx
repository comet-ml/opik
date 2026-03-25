import React from "react";
import { CopyPlus, Pencil, Trash } from "lucide-react";

import { Button } from "@/ui/button";
import { Tag } from "@/ui/tag";
import { Separator } from "@/ui/separator";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";
import { DropdownOption } from "@/types/shared";

export type InsightsViewOption = DropdownOption<string> & {
  icon: React.ComponentType<{ className?: string }>;
  iconColor: string;
  isBuiltIn: boolean;
};

export interface BuiltInViewItemProps {
  option: InsightsViewOption;
  isSelected: boolean;
  onSelect: (id: string) => void;
  onDuplicate: () => void;
}

export const BuiltInViewItem: React.FC<BuiltInViewItemProps> = ({
  option,
  isSelected,
  onSelect,
  onDuplicate,
}) => {
  const Icon = option.icon;

  return (
    <div
      className={cn(
        "group cursor-pointer rounded px-4 py-2.5 hover:bg-primary-foreground",
        isSelected && "bg-primary-foreground",
      )}
      onClick={() => onSelect(option.value)}
    >
      <div className="flex flex-col gap-0.5">
        <div className="flex items-center justify-between">
          <div className="flex min-w-0 items-center gap-2">
            <Icon className={cn("size-4 shrink-0", option.iconColor)} />
            <span className="comet-body-s-accented truncate text-foreground">
              {option.label}
            </span>
            <Tag variant="pink" size="sm" className="shrink-0">
              Built-in
            </Tag>
          </div>
          <div className="shrink-0 opacity-0 group-hover:opacity-100">
            <TooltipWrapper content="Duplicate">
              <Button
                variant="minimal"
                size="icon-xs"
                className="text-muted-slate hover:text-foreground"
                onClick={(e) => {
                  e.stopPropagation();
                  onDuplicate();
                }}
              >
                <CopyPlus className="size-3.5" />
              </Button>
            </TooltipWrapper>
          </div>
        </div>
        <div className="comet-body-s text-light-slate">
          {option.description}
        </div>
      </div>
    </div>
  );
};

export interface CustomViewItemProps {
  option: InsightsViewOption;
  isSelected: boolean;
  onSelect: (id: string) => void;
  onEdit: () => void;
  onDuplicate: () => void;
  onDelete: () => void;
}

export const CustomViewItem: React.FC<CustomViewItemProps> = ({
  option,
  isSelected,
  onSelect,
  onEdit,
  onDuplicate,
  onDelete,
}) => {
  const Icon = option.icon;

  return (
    <div
      className={cn(
        "group cursor-pointer rounded px-4 py-2.5 hover:bg-primary-foreground",
        isSelected && "bg-primary-foreground",
      )}
      onClick={() => onSelect(option.value)}
    >
      <div className="flex flex-col gap-0.5">
        <div className="flex items-center justify-between">
          <div className="flex min-w-0 items-center gap-2 pr-2">
            <Icon className={cn("size-4 shrink-0", option.iconColor)} />
            <span className="comet-body-s-accented truncate text-foreground">
              {option.label}
            </span>
          </div>
          <div className="flex shrink-0 items-center gap-1.5 rounded-sm p-0.5 opacity-0 group-hover:opacity-100">
            <TooltipWrapper content="Edit">
              <Button
                variant="minimal"
                size="icon-xs"
                className="text-muted-slate hover:text-foreground"
                onClick={(e) => {
                  e.stopPropagation();
                  onEdit();
                }}
              >
                <Pencil className="size-3.5" />
              </Button>
            </TooltipWrapper>
            <TooltipWrapper content="Duplicate">
              <Button
                variant="minimal"
                size="icon-xs"
                className="text-muted-slate hover:text-foreground"
                onClick={(e) => {
                  e.stopPropagation();
                  onDuplicate();
                }}
              >
                <CopyPlus className="size-3.5" />
              </Button>
            </TooltipWrapper>
            <Separator orientation="vertical" className="h-2.5" />
            <TooltipWrapper content="Delete">
              <Button
                variant="minimal"
                size="icon-xs"
                className="text-muted-slate hover:text-destructive"
                onClick={(e) => {
                  e.stopPropagation();
                  onDelete();
                }}
              >
                <Trash className="size-3.5" />
              </Button>
            </TooltipWrapper>
          </div>
        </div>
        <div className="comet-body-s truncate text-light-slate">
          {option.description}
        </div>
      </div>
    </div>
  );
};
