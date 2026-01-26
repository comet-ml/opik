import React from "react";
import { Calendar, Check, Settings } from "lucide-react";

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
import {
  useDateFormat,
  DATE_FORMATS,
  DATE_FORMAT_LABELS,
  DATE_FORMAT_EXAMPLES,
  DateFormatType,
} from "@/hooks/useDateFormat";

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
        <DropdownMenuGroup>
          <DropdownMenuSub>
            <DropdownMenuSubTrigger className="flex cursor-pointer items-center">
              <Calendar className="mr-2 size-4" />
              <span>Date format</span>
            </DropdownMenuSubTrigger>
            <DropdownMenuPortal>
              <DropdownMenuSubContent className="w-64">
                {Object.entries(DATE_FORMATS).map(([key, format]) => (
                  <DropdownMenuItem
                    key={key}
                    className="cursor-pointer"
                    onClick={() => setDateFormat(format as DateFormatType)}
                  >
                    <div className="relative flex w-full flex-col pl-6">
                      {dateFormat === format && (
                        <Check className="absolute left-0 top-1/2 size-4 -translate-y-1/2" />
                      )}
                      <span className="comet-body-s">
                        {DATE_FORMAT_LABELS[format]}
                      </span>
                      <span className="comet-body-xs text-muted-foreground">
                        {DATE_FORMAT_EXAMPLES[format]}
                      </span>
                    </div>
                  </DropdownMenuItem>
                ))}
              </DropdownMenuSubContent>
            </DropdownMenuPortal>
          </DropdownMenuSub>
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default SettingsMenu;
