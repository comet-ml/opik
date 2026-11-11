import React from "react";
import { Plus } from "lucide-react";

import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import MenuOptionItems, {
  MenuOption,
} from "@/shared/MenuOptionItems/MenuOptionItems";

export type CreateMenuOption<T extends string> = MenuOption<T>;

type CreateMenuButtonProps<T extends string> = {
  label: string;
  options: CreateMenuOption<T>[];
  onSelect: (value: T) => void;
  "data-testid"?: string;
};

const CreateMenuButton = <T extends string>({
  label,
  options,
  onSelect,
  "data-testid": dataTestId,
}: CreateMenuButtonProps<T>) => {
  const hasMenu = options.length > 1;

  const button = (
    <Button
      variant="default"
      size="xs"
      data-testid={dataTestId}
      onClick={hasMenu ? undefined : () => onSelect(options[0].value)}
    >
      <Plus className="mr-1 size-4" />
      <span>{label}</span>
    </Button>
  );

  if (!hasMenu) return button;

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>{button}</DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-60 p-1.5">
        <MenuOptionItems options={options} onSelect={onSelect} />
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default CreateMenuButton;
