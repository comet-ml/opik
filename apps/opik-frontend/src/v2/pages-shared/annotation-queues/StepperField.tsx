import React from "react";

import { cn } from "@/lib/utils";
import { Input } from "@/ui/input";
import { ChevronDown, ChevronUp } from "lucide-react";

type StepperFieldProps = React.ComponentProps<typeof Input> & {
  /** Unit shown after the value, reading as part of it - "5 min" rather than a separate label. */
  suffix?: string;
};

/**
 * A number field drawn the way the design draws it: the unit reads as part of the value, and the
 * stepper is an explicit glyph rather than the browser's hover-only spinner.
 *
 * The glyph is wired to stepUp/stepDown so it does what it looks like it does - a decorative stepper
 * that ignored clicks would be worse than no stepper at all.
 */
const StepperField = React.forwardRef<HTMLInputElement, StepperFieldProps>(
  ({ suffix, className, ...props }, forwardedRef) => {
    const inputRef = React.useRef<HTMLInputElement | null>(null);

    // FormControl hands its ref down through a Slot for the field's aria wiring and focus-on-error,
    // and the stepper needs the same node - so both get it.
    const setRef = (node: HTMLInputElement | null) => {
      inputRef.current = node;
      if (typeof forwardedRef === "function") {
        forwardedRef(node);
      } else if (forwardedRef) {
        forwardedRef.current = node;
      }
    };

    const step = (direction: "up" | "down") => {
      const input = inputRef.current;
      if (!input) return;

      if (direction === "up") {
        input.stepUp();
      } else {
        input.stepDown();
      }

      // React listens for input events at the root, so the form only sees the new value if the
      // programmatic step announces itself.
      input.dispatchEvent(new Event("input", { bubbles: true }));
    };

    return (
      <div
        className={cn(
          "flex h-8 items-center gap-1 rounded-md border border-border bg-background px-3 hover:shadow-sm focus-within:border-primary",
          className,
        )}
      >
        <Input
          ref={setRef}
          variant="unstyled"
          dimension="none"
          type="number"
          className="w-9 [appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
          {...props}
        />
        {suffix && (
          <span className="comet-body-s text-foreground">{suffix}</span>
        )}
        <span className="ml-auto flex shrink-0 flex-col text-light-slate">
          <button
            type="button"
            aria-label="Increase"
            className="flex h-2 items-center hover:text-foreground"
            onClick={() => step("up")}
          >
            <ChevronUp className="size-3.5" />
          </button>
          <button
            type="button"
            aria-label="Decrease"
            className="flex h-2 items-center hover:text-foreground"
            onClick={() => step("down")}
          >
            <ChevronDown className="size-3.5" />
          </button>
        </span>
      </div>
    );
  },
);
StepperField.displayName = "StepperField";

export default StepperField;
