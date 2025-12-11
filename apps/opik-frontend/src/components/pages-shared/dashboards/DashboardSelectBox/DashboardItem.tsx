import React from "react";
import {
  BarChart3,
  Check,
  Copy,
  MoreVertical,
  Pencil,
  Trash2,
} from "lucide-react";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { Dashboard } from "@/types/dashboard";

interface DashboardItemProps {
  dashboard: Dashboard;
  isSelected: boolean;
  onSelect: (id: string) => void;
  onEdit: (dashboard: Dashboard) => void;
  onDuplicate: (dashboard: Dashboard) => void;
  onDelete: (dashboard: Dashboard) => void;
}

export const DashboardItem: React.FC<DashboardItemProps> = ({
  dashboard,
  isSelected,
  onSelect,
  onEdit,
  onDuplicate,
  onDelete,
}) => {
  return (
    <div
      className={cn(
        "group flex min-h-12 cursor-pointer items-center gap-2 rounded-md px-4 py-2 hover:bg-primary-foreground",
        isSelected && "bg-primary-foreground",
      )}
      onClick={() => onSelect(dashboard.id)}
    >
      <div className="flex min-w-4 items-center justify-center">
        {isSelected && <Check className="size-3.5 shrink-0" strokeWidth="3" />}
      </div>
      <BarChart3 className="size-4 shrink-0 text-muted-slate" />
      <div className="flex min-w-0 flex-1 flex-col">
        <div className="comet-body-s truncate">{dashboard.name}</div>
        {dashboard.description && (
          <div className="comet-body-xs truncate text-muted-foreground">
            {dashboard.description}
          </div>
        )}
      </div>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            variant="ghost"
            size="icon-xs"
            className="opacity-0 group-hover:opacity-100"
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
              onEdit(dashboard);
            }}
          >
            <Pencil className="mr-2 size-4" />
            Edit
          </DropdownMenuItem>
          <DropdownMenuItem
            onClick={(e) => {
              e.stopPropagation();
              onDuplicate(dashboard);
            }}
          >
            <Copy className="mr-2 size-4" />
            Duplicate
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem
            onClick={(e) => {
              e.stopPropagation();
              onDelete(dashboard);
            }}
            className="text-destructive focus:text-destructive"
          >
            <Trash2 className="mr-2 size-4" />
            Delete
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
};
