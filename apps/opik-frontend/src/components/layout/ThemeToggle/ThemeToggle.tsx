import React from "react";
import { Check } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useThemeOptions } from "@/hooks/useThemeOptions";

const ThemeToggle = () => {
  const { theme, themeOptions, currentOption, CurrentIcon, handleThemeSelect } =
    useThemeOptions();

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="icon-sm">
          <CurrentIcon className="size-[1.2rem]" />
          <span className="sr-only">
            Current theme: {currentOption?.label || "Unknown"}
          </span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {themeOptions.map(({ value, label, icon: Icon }) => (
          <DropdownMenuItem
            key={value}
            onClick={() => handleThemeSelect(value)}
          >
            <div className="relative flex w-full items-center pl-6">
              {theme === value && <Check className="absolute left-0 size-4" />}
              <Icon className="mr-2 size-4" />
              <span>{label}</span>
            </div>
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default ThemeToggle;
