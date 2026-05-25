import { cn } from "@/lib/utils";

export const cellBase =
  "comet-body-xs h-5 border border-solid border-secondary bg-primary-50 px-2 py-0.5 text-foreground outline-none transition-colors";

export const cellButton = cn(
  cellBase,
  "inline-flex shrink-0 items-center gap-1 whitespace-nowrap",
  "hover:bg-primary-100 hover:text-primary-hover",
  "focus-visible:bg-primary-100 focus-visible:text-primary-active",
  "data-[state=open]:bg-primary-100 data-[state=open]:text-primary-active",
);

export const cellInput = cn(
  cellBase,
  "min-w-0 placeholder:text-light-slate",
  "focus:border-primary focus:bg-background",
);
