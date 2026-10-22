import React from "react";
import { ChevronDown, LayoutGrid, Plus } from "lucide-react";
import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import OllieIcon from "@/icons/ollie-owl.svg?react";
import useAskOllieForWidget from "@/shared/Dashboard/useAskOllieForWidget";

interface AddWidgetButtonProps {
  onAddWidget: () => void;
  onMenuOpenChange?: (open: boolean) => void;
}

const AddWidgetButton: React.FC<AddWidgetButtonProps> = ({
  onAddWidget,
  onMenuOpenChange,
}) => {
  const { isOllieAvailable, askOllie } = useAskOllieForWidget();

  if (!isOllieAvailable) {
    return (
      <Button
        variant="outline"
        size="sm"
        onClick={(e) => {
          e.stopPropagation();
          onAddWidget();
        }}
        className="gap-1.5"
      >
        <Plus className="size-3.5" />
        Add widget
      </Button>
    );
  }

  return (
    <DropdownMenu onOpenChange={onMenuOpenChange}>
      <DropdownMenuTrigger asChild>
        <Button
          variant="outline"
          size="sm"
          onClick={(e) => e.stopPropagation()}
          className="gap-1.5"
        >
          <Plus className="size-3.5" />
          Add widget
          <ChevronDown className="size-3.5 text-light-slate" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent
        align="end"
        className="w-72"
        onClick={(e) => e.stopPropagation()}
      >
        <DropdownMenuItem
          className="items-start gap-2.5 py-2.5"
          onSelect={onAddWidget}
        >
          <LayoutGrid className="mt-0.5 size-4 shrink-0 text-light-slate" />
          <div className="flex flex-1 flex-col gap-0.5">
            <span className="comet-body-s-accented">Add widget</span>
            <span className="comet-body-xs text-light-slate">
              Pick a built-in chart
            </span>
          </div>
        </DropdownMenuItem>
        <DropdownMenuItem
          className="items-start gap-2.5 py-2.5"
          onSelect={() => askOllie("add_widget_menu")}
        >
          <OllieIcon className="mt-0.5 size-4 shrink-0 text-[var(--color-ollie)]" />
          <div className="flex flex-1 flex-col gap-0.5">
            <span className="comet-body-s-accented">Describe a widget</span>
            <span className="comet-body-xs text-light-slate">
              Ollie builds a custom chart for you
            </span>
          </div>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default AddWidgetButton;
