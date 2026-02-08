import React from "react";
import { Check, Settings } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuPortal,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useThemeOptions } from "@/hooks/useThemeOptions";
import { useDateFormat } from "@/hooks/useDateFormat";
import DateFormatDropdown from "@/components/shared/DateFormatDropdown/DateFormatDropdown";

const SettingsMenu = () => {
  const { theme, themeOptions, CurrentIcon, handleThemeSelect } =
    useThemeOptions();
  const [dateFormat, setDateFormat] = useDateFormat();

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="icon-sm">
          <Settings className="size-4" />
          <span className="sr-only">Settings</span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-48">
        <DropdownMenuGroup>
          <DropdownMenuSub>
            <DropdownMenuSubTrigger className="flex cursor-pointer items-center">
              <CurrentIcon className="mr-2 size-4" />
              <span>Theme</span>
            </DropdownMenuSubTrigger>
            <DropdownMenuPortal>
              <DropdownMenuSubContent>
                {themeOptions.map(({ value, label, icon: Icon }) => (
                  <DropdownMenuItem
                    key={value}
                    className="cursor-pointer"
                    onClick={() => handleThemeSelect(value)}
                  >
                    <div className="relative flex w-full items-center pl-6">
                      {theme === value && (
                        <Check className="absolute left-0 size-4" />
                      )}
                      <Icon className="mr-2 size-4" />
                      <span>{label}</span>
                    </div>
                  </DropdownMenuItem>
                ))}
              </DropdownMenuSubContent>
            </DropdownMenuPortal>
          </DropdownMenuSub>
        </DropdownMenuGroup>
        <DateFormatDropdown
          dateFormat={dateFormat}
          setDateFormat={setDateFormat}
        />
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default SettingsMenu;
