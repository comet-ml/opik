import { Check, Copy, Settings2 } from "lucide-react";
import copy from "clipboard-copy";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuPortal,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useThemeOptions } from "@/hooks/useThemeOptions";
import { APP_VERSION } from "@/constants/app";
import { toast } from "../ui/use-toast";

const NoUserMenu = () => {
  const { theme, themeOptions, currentOption, CurrentIcon, handleThemeSelect } =
    useThemeOptions();

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="icon-sm">
          <Settings2 className="size-[1.2rem]" />
          <span className="sr-only">
            Current theme: {currentOption?.label || "Unknown"}
          </span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
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
        {APP_VERSION && (
          <>
            <DropdownMenuSeparator />
            <DropdownMenuItem
              className="cursor-pointer justify-center text-light-slate"
              onClick={() => {
                copy(APP_VERSION);
                toast({ description: "Successfully copied version" });
              }}
            >
              <span className="comet-body-xs-accented truncate ">
                VERSION {APP_VERSION}
              </span>
              <Copy className="ml-2 size-3 shrink-0" />
            </DropdownMenuItem>
          </>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default NoUserMenu;
