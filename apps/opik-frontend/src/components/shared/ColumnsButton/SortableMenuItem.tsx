import React from "react";
import { useSortable } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { DropdownMenuCustomCheckboxItem } from "@/components/ui/dropdown-menu";
import { GripHorizontal } from "lucide-react";
import { cn } from "@/lib/utils";

type SortableMenuItemProps = {
  id: string;
  label: string;
  checked: boolean;
  disabled?: boolean;
  onCheckboxChange: (id: string) => void;
};

const SortableMenuItem: React.FunctionComponent<SortableMenuItemProps> = ({
  id,
  label,
  checked,
  disabled,
  onCheckboxChange,
}) => {
  const { active, attributes, listeners, setNodeRef, transform, transition } =
    useSortable({ id });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
  };

  return (
    <DropdownMenuCustomCheckboxItem
      ref={setNodeRef}
      style={style}
      {...attributes}
      className={cn("group", {
        "z-10": id === active?.id,
      })}
      checked={checked}
      onCheckedChange={() => onCheckboxChange(id)}
      onSelect={(event) => event.preventDefault()}
      disabled={disabled}
    >
      <div className="flex w-full flex-row items-center justify-between py-2">
        {label}
        <GripHorizontal
          className="ml-2 hidden size-4 cursor-move text-light-slate group-hover:block"
          {...listeners}
        />
      </div>
    </DropdownMenuCustomCheckboxItem>
  );
};

export default SortableMenuItem;
