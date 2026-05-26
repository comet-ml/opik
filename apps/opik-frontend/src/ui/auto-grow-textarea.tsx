import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "@/lib/utils";

const autoGrowTextareaVariants = cva(
  "flex w-full resize-none overflow-hidden rounded-md bg-background text-sm text-foreground placeholder:text-light-slate focus-visible:outline-none disabled:cursor-not-allowed disabled:bg-muted-disabled disabled:text-muted-gray disabled:placeholder:text-muted-gray",
  {
    variants: {
      variant: {
        default:
          "border border-border invalid:border-warning hover:shadow-sm focus-visible:border-primary focus-visible:invalid:border-warning hover:disabled:shadow-none",
        ghost: "",
      },
      dimension: {
        default: "min-h-10 px-3 py-2 leading-6",
        sm: "min-h-8 px-3 py-1.5 leading-5",
      },
    },
    defaultVariants: {
      variant: "default",
      dimension: "default",
    },
  },
);

export interface AutoGrowTextareaProps
  extends Omit<
      React.TextareaHTMLAttributes<HTMLTextAreaElement>,
      "rows" | "onChange"
    >,
    VariantProps<typeof autoGrowTextareaVariants> {
  value: string;
  onChange?: (value: string) => void;
}

const AutoGrowTextarea = React.forwardRef<
  HTMLTextAreaElement,
  AutoGrowTextareaProps
>(({ className, variant, dimension, value, onChange, ...props }, ref) => {
  const innerRef = React.useRef<HTMLTextAreaElement>(null);
  React.useImperativeHandle(ref, () => innerRef.current as HTMLTextAreaElement);

  const resize = React.useCallback(() => {
    const el = innerRef.current;
    if (!el) return;
    el.style.height = "0";
    const border = el.offsetHeight - el.clientHeight;
    el.style.height = el.scrollHeight + border + "px";
  }, []);

  React.useEffect(() => {
    resize();
    const el = innerRef.current;
    if (!el) return;
    let lastWidth = el.offsetWidth;
    const observer = new ResizeObserver(() => {
      if (el.offsetWidth !== lastWidth) {
        lastWidth = el.offsetWidth;
        resize();
      }
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, [value, resize]);

  return (
    <textarea
      ref={innerRef}
      rows={1}
      className={cn(
        autoGrowTextareaVariants({ variant, dimension, className }),
      )}
      value={value}
      onChange={(e) => onChange?.(e.target.value)}
      {...props}
    />
  );
});
AutoGrowTextarea.displayName = "AutoGrowTextarea";

export { AutoGrowTextarea };
