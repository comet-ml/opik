import * as React from "react";

import { cn } from "@/lib/utils";

export const TEXT_AREA_CLASSES =
  "flex min-h-44 w-full rounded-md border border-input bg-background px-3 py-2 text-sm placeholder:text-light-slate invalid:border-warning hover:shadow-sm focus-visible:border-primary focus-visible:outline-none focus-visible:invalid:border-warning disabled:cursor-not-allowed disabled:bg-muted-disabled disabled:text-muted-gray disabled:placeholder:text-muted-gray hover:disabled:shadow-none";

export interface TextareaProps
  extends React.TextareaHTMLAttributes<HTMLTextAreaElement> {}

const Textarea = React.forwardRef<HTMLTextAreaElement, TextareaProps>(
  ({ className, ...props }, ref) => {
    return (
      <textarea
        className={cn(TEXT_AREA_CLASSES, className)}
        ref={ref}
        {...props}
      />
    );
  },
);
Textarea.displayName = "Textarea";

export { Textarea };
