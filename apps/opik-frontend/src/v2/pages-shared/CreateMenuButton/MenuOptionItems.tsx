import React from "react";
import { LucideIcon } from "lucide-react";

import { DropdownMenuItem, DropdownMenuSeparator } from "@/ui/dropdown-menu";
import IconTag from "@/v2/pages-shared/IconTag/IconTag";

export type MenuOption<T extends string> = {
  value: T;
  label: string;
  description: string;
  icon: LucideIcon;
  iconClassName: string;
};

type MenuOptionItemsProps<T extends string> = {
  options: MenuOption<T>[];
  onSelect: (value: T) => void;
};

const MenuOptionItems = <T extends string>({
  options,
  onSelect,
}: MenuOptionItemsProps<T>) => (
  <>
    {options.map(
      ({ value, label, description, icon, iconClassName }, index) => (
        <React.Fragment key={value}>
          {index > 0 && <DropdownMenuSeparator />}
          <DropdownMenuItem
            className="flex-col items-start gap-0.5 p-1"
            onClick={() => onSelect(value)}
          >
            <div className="flex items-center gap-1">
              <IconTag icon={icon} className={iconClassName} />
              <span className="comet-body-xs-accented">{label}</span>
            </div>
            <span className="comet-body-xs text-muted-slate">
              {description}
            </span>
          </DropdownMenuItem>
        </React.Fragment>
      ),
    )}
  </>
);

export default MenuOptionItems;
