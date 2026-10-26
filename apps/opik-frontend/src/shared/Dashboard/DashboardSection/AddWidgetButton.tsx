import React from "react";
import { Plus } from "lucide-react";
import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import MenuOptionItems from "@/shared/MenuOptionItems/MenuOptionItems";
import useAskOllieForWidget from "@/shared/Dashboard/useAskOllieForWidget";
import { ADD_WIDGET_OPTIONS } from "./addWidgetOptions";

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
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent
        align="end"
        className="w-60 p-1.5"
        onClick={(e) => e.stopPropagation()}
      >
        <MenuOptionItems
          options={ADD_WIDGET_OPTIONS}
          onSelect={(value) =>
            value === "add" ? onAddWidget() : askOllie("add_widget_menu")
          }
        />
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default AddWidgetButton;
