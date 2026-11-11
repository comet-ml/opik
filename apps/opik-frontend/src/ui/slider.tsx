import * as React from "react";
import * as SliderPrimitive from "@radix-ui/react-slider";

import { cn } from "@/lib/utils";

export type SliderSize = "default" | "sm";

const SIZE_CLASSES: Record<
  SliderSize,
  { root: string; track: string; thumb: string }
> = {
  default: {
    root: "",
    track: "h-2",
    thumb: "size-4 border-2 border-background",
  },
  sm: {
    root: "h-3",
    track: "h-1",
    thumb: "size-3 border-2 border-soft-background shadow-sm",
  },
};

const Slider = React.forwardRef<
  React.ElementRef<typeof SliderPrimitive.Root>,
  React.ComponentPropsWithoutRef<typeof SliderPrimitive.Root> & {
    size?: SliderSize;
  }
>(({ className, size = "default", ...props }, ref) => (
  <SliderPrimitive.Root
    ref={ref}
    className={cn(
      "relative flex w-full touch-none select-none items-center",
      SIZE_CLASSES[size].root,
      className,
    )}
    {...props}
  >
    <SliderPrimitive.Track
      className={cn(
        "relative w-full grow overflow-hidden rounded-full bg-muted",
        SIZE_CLASSES[size].track,
      )}
    >
      <SliderPrimitive.Range className="absolute h-full bg-primary" />
    </SliderPrimitive.Track>
    <SliderPrimitive.Thumb
      className={cn(
        "block rounded-full bg-primary ring-offset-background transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50",
        SIZE_CLASSES[size].thumb,
      )}
    />
  </SliderPrimitive.Root>
));
Slider.displayName = SliderPrimitive.Root.displayName;

export { Slider };
