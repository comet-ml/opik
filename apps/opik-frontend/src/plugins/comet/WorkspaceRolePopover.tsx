import React from "react";
import { Checkbox } from "@/components/ui/checkbox";
import { SelectContent, SelectItem } from "@/components/ui/select";
import { cn } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import useManageUsersRolePopover from "@/plugins/comet/WorkspaceRoleCell/useManageUsersRolePopover";

const WorkspaceRolePopover = ({
  controlValue,
  options,
}: ReturnType<typeof useManageUsersRolePopover>) => {
  return (
    <SelectContent
      align="start"
      position="item-aligned"
      className="w-[280px] p-2"
    >
      {options.map((option) => {
        const isSelected = controlValue === option.value;
        const selectItem = (
          <SelectItem
            key={option.key}
            value={option.value}
            disabled={option.disabled}
            description={option.text}
          >
            <span className="font-medium">{option.label}</span>
          </SelectItem>
        );

        const itemWithTooltip = option.tooltip ? (
          <TooltipWrapper
            content={option.tooltip.title}
            side={option.tooltip.placement || "left"}
          >
            <div>{selectItem}</div>
          </TooltipWrapper>
        ) : (
          selectItem
        );

        return (
          <React.Fragment key={option.key}>
            {itemWithTooltip}
            {option.list && isSelected && (
              <div className="ml-7 mt-1 space-y-2 px-2 pb-2">
                {option.list.options.map((checkboxOption) => (
                  <label
                    key={checkboxOption.key}
                    htmlFor={checkboxOption.key}
                    className={cn(
                      "comet-body-s flex cursor-pointer select-none items-start gap-3 rounded-sm p-2 outline-none transition-colors hover:bg-accent hover:text-accent-foreground focus-within:bg-accent focus-within:text-accent-foreground",
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
                      <span className="mt-0.5 text-sm text-light-slate">
                        {checkboxOption.text}
                      </span>
                    </div>
                  </label>
                ))}
              </div>
            )}
          </React.Fragment>
        );
      })}
    </SelectContent>
  );
};

export default WorkspaceRolePopover;
