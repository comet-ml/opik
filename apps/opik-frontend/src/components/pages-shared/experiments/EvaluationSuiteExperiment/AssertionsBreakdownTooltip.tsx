import React, { Fragment, type ReactNode } from "react";
import { CheckCheck } from "lucide-react";

import {
  Tooltip,
  TooltipContent,
  TooltipPortal,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { Separator } from "@/components/ui/separator";
import { Tag } from "@/components/ui/tag";
import { AssertionResult } from "@/types/datasets";

type AssertionsBreakdownTooltipProps = {
  children: ReactNode;
  assertionsByRun: AssertionResult[][];
};

export const AssertionsBreakdownTooltip: React.FC<
  AssertionsBreakdownTooltipProps
> = ({ children, assertionsByRun }) => {
  if (assertionsByRun.length === 0 || assertionsByRun[0].length === 0) {
    return <>{children}</>;
  }

  const isMultiRun = assertionsByRun.length > 1;
  const assertionNames = assertionsByRun[0].map((a) => a.value);
  const runCount = assertionsByRun.length;
  const assertionMaps = assertionsByRun.map(
    (run) => new Map(run.map((a) => [a.value, a])),
  );

  return (
    <Tooltip>
      <TooltipTrigger asChild>{children}</TooltipTrigger>
      <TooltipPortal>
        <TooltipContent
          side="bottom"
          collisionPadding={16}
          className="max-w-[600px] overflow-x-auto p-0"
          onClick={(e) => e.stopPropagation()}
        >
          <div
            className="grid items-center gap-x-2 p-2"
            style={{
              gridTemplateColumns: `auto repeat(${runCount}, 64px)`,
            }}
          >
            <div className="flex items-center gap-1.5 px-2 pb-0.5 pt-1">
              <div className="flex size-4 items-center justify-center rounded bg-[#89DEFF]">
                <CheckCheck className="size-3 text-foreground" />
              </div>
              <span className="comet-body-xs-accented text-foreground">
                Assertions
              </span>
            </div>
            {isMultiRun &&
              assertionsByRun.map((_, runIdx) => (
                <span
                  key={runIdx}
                  className="comet-body-xs-accented pb-0.5 pt-1 text-center text-muted-slate"
                >
                  Run {runIdx + 1}
                </span>
              ))}

            <Separator className="col-span-full my-1" />

            {assertionNames.map((name, aIdx) => (
              <Fragment key={name}>
                <div className="flex items-start gap-1.5 px-2 py-1">
                  <div className="mt-[5px] size-[7px] shrink-0 rounded-[1.5px] bg-[#89DEFF]" />
                  <span className="comet-body-xs whitespace-nowrap text-muted-slate">
                    {name}
                  </span>
                </div>
                {assertionsByRun.map((run, runIdx) => {
                  const passed =
                    assertionMaps[runIdx].get(name)?.passed ?? false;
                  return (
                    <div key={runIdx} className="flex justify-center py-1">
                      <Tag variant={passed ? "green" : "red"} size="sm">
                        {passed ? "Passed" : "Failed"}
                      </Tag>
                    </div>
                  );
                })}
                {aIdx < assertionNames.length - 1 && (
                  <Separator className="col-span-full my-1 bg-[var(--separator-light)]" />
                )}
              </Fragment>
            ))}
          </div>
        </TooltipContent>
      </TooltipPortal>
    </Tooltip>
  );
};

export default AssertionsBreakdownTooltip;
