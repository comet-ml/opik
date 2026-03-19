import React, { forwardRef } from "react";
import TextareaAutosize, {
  TextareaAutosizeProps,
} from "react-textarea-autosize";
import { Globe, X } from "lucide-react";

import { cn } from "@/lib/utils";
import {
  Tooltip,
  TooltipContent,
  TooltipPortal,
  TooltipTrigger,
} from "@/components/ui/tooltip";

interface AssertionFieldProps
  extends Omit<TextareaAutosizeProps, "className" | "disabled" | "readOnly"> {
  isReadOnly?: boolean;
  onRemove?: () => void;
}

const WRAPPER_CLASSES =
  "flex items-stretch rounded-md border border-border focus-within:border-primary focus-within:ring-0 transition-colors";

const EDITABLE_WRAPPER_CLASSES = "bg-background";

const READONLY_WRAPPER_CLASSES =
  "border-dashed bg-gray-100 focus-within:border-border";

const TEXTAREA_CLASSES =
  "flex w-full rounded-l-md border-none bg-transparent px-3 py-1.5 text-sm text-foreground placeholder:text-light-slate focus:outline-none min-h-8 resize-none";

const READONLY_TEXTAREA_CLASSES = "text-muted-slate cursor-default";

const BUTTON_CLASSES =
  "flex shrink-0 items-center justify-center w-8 min-h-8 self-stretch rounded-r-md border-l border-border hover:bg-muted-disabled/50 transition-colors";

const READONLY_BUTTON_CLASSES =
  "border-l-0 cursor-default hover:bg-transparent";

const GLOBAL_ASSERTION_TOOLTIP =
  "This is a global assertion and can't be removed from here. You can manage them from the settings menu.";

const AssertionField = forwardRef<HTMLTextAreaElement, AssertionFieldProps>(
  ({ isReadOnly = false, onRemove, ...textareaProps }, ref) => {
    return (
      <div
        className={cn(
          WRAPPER_CLASSES,
          isReadOnly ? READONLY_WRAPPER_CLASSES : EDITABLE_WRAPPER_CLASSES,
        )}
      >
        <TextareaAutosize
          ref={ref}
          minRows={1}
          maxRows={6}
          readOnly={isReadOnly}
          className={cn(
            TEXTAREA_CLASSES,
            isReadOnly && READONLY_TEXTAREA_CLASSES,
          )}
          {...textareaProps}
        />
        {isReadOnly ? (
          <Tooltip>
            <TooltipTrigger asChild>
              <div className={cn(BUTTON_CLASSES, READONLY_BUTTON_CLASSES)}>
                <Globe className="size-3.5 text-muted-slate opacity-50" />
              </div>
            </TooltipTrigger>
            <TooltipPortal>
              <TooltipContent side="top" className="max-w-[240px]">
                {GLOBAL_ASSERTION_TOOLTIP}
              </TooltipContent>
            </TooltipPortal>
          </Tooltip>
        ) : (
          <button type="button" className={BUTTON_CLASSES} onClick={onRemove}>
            <X className="size-3.5 text-muted-slate" />
          </button>
        )}
      </div>
    );
  },
);

AssertionField.displayName = "AssertionField";

export default AssertionField;
