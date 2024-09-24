import * as React from "react";

import { cn } from "@/lib/utils";

export interface TextareaProps
  extends React.TextareaHTMLAttributes<HTMLTextAreaElement> {}

const Textarea = React.forwardRef<HTMLTextAreaElement, TextareaProps>(
  ({ className, ...props }, ref) => {
    return (
      <textarea
        className={cn(
          "flex min-h-44 w-full rounded-md border border-input bg-background px-3 py-2 text-sm placeholder:text-light-slate hover:shadow-sm hover:disabled:shadow-none focus-visible:outline-none focus-visible:border-primary disabled:cursor-not-allowed disabled:text-muted-gray disabled:bg-muted-disabled disabled:placeholder:text-muted-gray",
          className,
        )}
        ref={ref}
        {...props}
      />
    );
  },
);
Textarea.displayName = "Textarea";

export { Textarea };
