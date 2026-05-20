import React from "react";

import { cn } from "@/lib/utils";

type PromptContentBlockProps = {
  toolbar: React.ReactNode;
  className?: string;
  bodyClassName?: string;
  children: React.ReactNode;
};

const PromptContentBlock: React.FC<PromptContentBlockProps> = ({
  toolbar,
  className,
  bodyClassName,
  children,
}) => (
  <div className={cn("rounded-md border bg-soft-background", className)}>
    <div className="flex h-8 items-center justify-between border-b px-3">
      {toolbar}
    </div>
    <div className={cn("p-3", bodyClassName)}>{children}</div>
  </div>
);

export default PromptContentBlock;
