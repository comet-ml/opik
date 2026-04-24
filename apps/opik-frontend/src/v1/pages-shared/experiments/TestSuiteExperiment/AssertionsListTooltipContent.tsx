import React, { Fragment } from "react";
import { CheckCheck } from "lucide-react";

import { Separator } from "@/ui/separator";

interface AssertionsListTooltipContentProps {
  assertions: string[];
}

export const AssertionsListTooltipContent: React.FC<
  AssertionsListTooltipContentProps
> = ({ assertions }) => {
  if (assertions.length === 0) {
    return null;
  }

  return (
    <div className="flex w-[250px] flex-col p-2">
      <div className="flex items-center gap-1.5 px-1 pb-0.5 pt-1">
        <div className="flex size-4 items-center justify-center rounded bg-[#89DEFF]">
          <CheckCheck className="size-3 text-foreground" />
        </div>
        <span className="comet-body-xs-accented text-foreground">
          Assertions
        </span>
      </div>
      <Separator className="my-1" />
      {assertions.map((assertion, index) => (
        <Fragment key={index}>
          <div className="flex items-start gap-1.5 px-2 py-1">
            <div className="mt-[5px] size-[7px] shrink-0 rounded-[1.5px] bg-[#89DEFF]" />
            <span className="comet-body-xs text-muted-slate">{assertion}</span>
          </div>
          {index < assertions.length - 1 && (
            <Separator className="my-1 bg-[var(--separator-light)]" />
          )}
        </Fragment>
      ))}
    </div>
  );
};

export default AssertionsListTooltipContent;
