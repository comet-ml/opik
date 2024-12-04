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
  disabledSorting?: boolean;
  onCheckboxChange: (id: string) => void;
};

const SortableMenuItem: React.FunctionComponent<SortableMenuItemProps> = ({
  id,
  label,
  checked,
  disabled,
  disabledSorting = false,
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
      <div className="relative w-full break-words py-2 pr-5">
        {label}
        {!disabledSorting ? (
          <GripHorizontal
            className="absolute right-0 top-[calc(50%-8px)] hidden size-4 cursor-move text-light-slate group-hover:block"
            {...listeners}
          />
        ) : (
          <div className="w-4"></div>
        )}
      </div>
    </DropdownMenuCustomCheckboxItem>
  );
};

export default SortableMenuItem;
