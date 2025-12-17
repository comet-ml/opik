import React from "react";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Checkbox } from "@/components/ui/checkbox";
import { DropdownMenuContent } from "@/components/ui/dropdown-menu";
import { cn } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import useManageUsersRolePopover from "@/plugins/comet/WorkspaceRoleCell/useManageUsersRolePopover";

const WorkspaceRolePopover = ({
  controlValue,
  onChange,
  options,
}: ReturnType<typeof useManageUsersRolePopover>) => {
  return (
    <DropdownMenuContent align="start" className="w-[280px] p-2">
      <RadioGroup
        value={controlValue || ""}
        onValueChange={(value) => {
          const syntheticEvent = {
            target: { value },
          } as React.ChangeEvent<HTMLInputElement>;
          onChange(syntheticEvent);
        }}
        className="gap-0"
      >
        {options.map((option) => {
          const radioItem = (
            <label
              htmlFor={option.key}
              className={cn(
                "comet-body-s flex cursor-pointer select-none items-start gap-3 rounded-sm p-3 outline-none transition-colors focus-within:bg-primary-foreground focus-within:text-foreground",
                option.disabled && "opacity-50 cursor-not-allowed",
              )}
            >
              <RadioGroupItem
                value={option.value}
                id={option.key}
                className="mt-0.5"
                disabled={option.disabled}
              />
              <div className="flex flex-1 flex-col">
                <span className="font-medium">{option.label}</span>
                <span className="mt-0.5 text-xs opacity-70">{option.text}</span>
              </div>
            </label>
          );

          const radioWithTooltip = option.tooltip ? (
            <TooltipWrapper
              content={option.tooltip.title}
              side={option.tooltip.placement || "left"}
            >
              {radioItem}
            </TooltipWrapper>
          ) : (
            radioItem
          );

          return (
            <div key={option.key}>
              {radioWithTooltip}
              {option.list && (
                <div className="ml-7 mt-1 space-y-2">
                  {option.list.options.map((checkboxOption) => (
                    <label
                      key={checkboxOption.key}
                      htmlFor={checkboxOption.key}
                      className={cn(
                        "comet-body-s flex cursor-pointer select-none items-start gap-3 rounded-sm p-2 outline-none transition-colors focus-within:bg-primary-foreground focus-within:text-foreground",
                      )}
                    >
                      <Checkbox
                        id={checkboxOption.key}
                        checked={checkboxOption.checked}
                        disabled={checkboxOption.disabled}
                        onCheckedChange={(checked) => {
                          const syntheticEvent = {
                            target: { checked },
                          } as React.ChangeEvent<HTMLInputElement>;
                          option.list?.onChange(syntheticEvent, {
                            value: checkboxOption.value,
                          });
                        }}
                        className="mt-0.5"
                      />
                      <div className="flex flex-1 flex-col">
                        <span className="font-medium">
                          {checkboxOption.label}
                        </span>
                        <span className="mt-0.5 text-xs opacity-70">
                          {checkboxOption.text}
                        </span>
                      </div>
                    </label>
                  ))}
                </div>
              )}
            </div>
          );
        })}
      </RadioGroup>
    </DropdownMenuContent>
  );
};

export default WorkspaceRolePopover;
