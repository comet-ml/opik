import React from "react";

import { cn } from "@/lib/utils";
import { Input } from "@/ui/input";

type StepperFieldProps = React.ComponentProps<typeof Input> & {
  /** Unit shown after the value, reading as part of it - "5 min" rather than a separate label. */
  suffix?: string;
};

/**
 * A number field in the shape the design uses for numeric filters (see the Duration filter's input):
 * one plain input, value right-aligned, the browser's own up/down arrows directly after the value, and
 * the unit as an inline suffix. One element rather than an input inside a styled box, so focus, hover
 * and dark mode are the Input's own.
 */
const StepperField = React.forwardRef<HTMLInputElement, StepperFieldProps>(
  ({ suffix, className, onFocus, ...props }, ref) => (
    <div className="relative">
      <Input
        ref={ref}
        type="number"
        dimension="sm"
        className={cn(
          "text-right [&::-webkit-inner-spin-button]:ml-2",
          suffix && "pr-12",
          className,
        )}
        onFocus={(event) => {
          // A number input has no setSelectionRange; re-assigning the value moves the caret after the digits,
          // where typing continues the number instead of prepending to it.
          const { value } = event.target;
          event.target.value = "";
          event.target.value = value;
          onFocus?.(event);
        }}
        {...props}
      />
      {suffix && (
        <span className="comet-body-s pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-muted-slate">
          {suffix}
        </span>
      )}
    </div>
  ),
);
StepperField.displayName = "StepperField";

export default StepperField;
