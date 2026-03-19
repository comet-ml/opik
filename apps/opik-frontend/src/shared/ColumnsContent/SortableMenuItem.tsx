import React from "react";
import { useSortable } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { Checkbox } from "@/ui/checkbox";
import { DropdownMenuCustomCheckboxItem } from "@/ui/dropdown-menu";
import { GripHorizontal } from "lucide-react";
import { cn } from "@/lib/utils";
export type ColumnsContentVariant = "menu" | "list";

type SortableMenuItemProps = {
  id: string;
  label: string;
  checked: boolean;
  disabled?: boolean;
  disabledSorting?: boolean;
  variant?: ColumnsContentVariant;
  onCheckboxChange: (id: string) => void;
};

const GripHandle: React.FunctionComponent<{
  disabledSorting: boolean;
  alwaysShowGrip: boolean;
  listeners: Record<string, unknown> | undefined;
}> = ({ disabledSorting, alwaysShowGrip, listeners }) => {
  if (disabledSorting) {
    return <div className="w-4"></div>;
  }

  return (
    <GripHorizontal
      className={cn(
        "absolute right-0 top-[calc(50%-8px)] size-4 cursor-move text-light-slate",
        !alwaysShowGrip && "hidden group-hover:block",
      )}
      {...listeners}
    />
  );
};

const SortableMenuItem: React.FunctionComponent<SortableMenuItemProps> = ({
  id,
  label,
  checked,
  disabled,
  disabledSorting = false,
  variant = "list",
  onCheckboxChange,
}) => {
  const alwaysShowGrip = variant === "list";

  const { active, attributes, listeners, setNodeRef, transform, transition } =
    useSortable({ id });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
  };

  if (variant === "menu") {
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
          <GripHandle
            disabledSorting={disabledSorting}
            alwaysShowGrip={alwaysShowGrip}
            listeners={listeners}
          />
        </div>
      </DropdownMenuCustomCheckboxItem>
    );
  }

  return (
    <div
      ref={setNodeRef}
      style={style}
      {...attributes}
      className={cn(
        "comet-body-s group relative flex min-h-10 cursor-pointer select-none items-center rounded-sm pl-10 pr-4 outline-none transition-colors hover:bg-primary-foreground hover:text-foreground break-all",
        {
          "z-10": id === active?.id,
          "pointer-events-none bg-muted-disabled text-muted-gray": disabled,
        },
      )}
      onClick={() => !disabled && onCheckboxChange(id)}
    >
      <span className="absolute left-2 flex size-8 items-center justify-center">
        <Checkbox checked={checked} disabled={disabled} tabIndex={-1} />
      </span>
      <div className="relative w-full break-words py-2 pr-5">
        {label}
        <GripHandle
          disabledSorting={disabledSorting}
          alwaysShowGrip={alwaysShowGrip}
          listeners={listeners}
        />
      </div>
    </div>
  );
};

export default SortableMenuItem;
