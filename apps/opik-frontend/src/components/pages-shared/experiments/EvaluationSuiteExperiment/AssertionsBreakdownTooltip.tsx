import React, { type ReactNode } from "react";
import { Check, X } from "lucide-react";

import {
  Tooltip,
  TooltipContent,
  TooltipPortal,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { AssertionResult } from "@/types/datasets";

type PassedIconProps = {
  passed: boolean;
};

const PassedIcon: React.FC<PassedIconProps> = ({ passed }) => {
  if (passed) {
    return <Check className="mx-auto size-3.5 text-green-600" />;
  }

  return <X className="mx-auto size-3.5 text-red-600" />;
};

function getColumnHeader(isMultiRun: boolean, runIndex: number): string {
  return isMultiRun ? `Passed? (${runIndex + 1})` : "Passed";
}

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
  const assertionNames = assertionsByRun[0].map((a) => a.name);

  return (
    <Tooltip>
      <TooltipTrigger asChild>{children}</TooltipTrigger>
      <TooltipPortal>
        <TooltipContent
          side="bottom"
          collisionPadding={16}
          className="max-w-fit p-0"
          onClick={(e) => e.stopPropagation()}
        >
          <Table className="w-full text-xs">
            <TableHeader>
              <TableRow className="text-muted-slate">
                <TableHead className="px-3 py-1.5 text-left font-medium">
                  Assertion
                </TableHead>
                {assertionsByRun.map((_, runIdx) => (
                  <TableHead
                    key={runIdx}
                    className="px-3 py-1.5 text-center font-medium"
                  >
                    {getColumnHeader(isMultiRun, runIdx)}
                  </TableHead>
                ))}
              </TableRow>
            </TableHeader>
            <TableBody>
              {assertionNames.map((name, aIdx) => (
                <TableRow key={aIdx}>
                  <TableCell className="max-w-48 truncate px-3 py-1.5">
                    {name}
                  </TableCell>
                  {assertionsByRun.map((run, runIdx) => (
                    <TableCell
                      key={runIdx}
                      className="px-3 py-1.5 text-center"
                    >
                      <PassedIcon passed={run[aIdx]?.passed ?? false} />
                    </TableCell>
                  ))}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TooltipContent>
      </TooltipPortal>
    </Tooltip>
  );
};

export default AssertionsBreakdownTooltip;
