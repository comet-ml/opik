import { type ReactNode } from "react";
import { Check, X } from "lucide-react";

import {
  Tooltip,
  TooltipContent,
  TooltipPortal,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { BehaviorResult } from "@/types/evaluation-suites";

type BehaviorsBreakdownTooltipProps = {
  children: ReactNode;
  behaviorsByRun: BehaviorResult[][];
};

function PassedIcon({ passed }: { passed: boolean }): ReactNode {
  if (passed) {
    return <Check className="mx-auto size-3.5 text-green-600" />;
  }

  return <X className="mx-auto size-3.5 text-red-600" />;
}

function getColumnHeader(isMultiRun: boolean, runIndex: number): string {
  if (isMultiRun) {
    return `Passed? (${runIndex + 1})`;
  }

  return "Passed";
}

const BehaviorsBreakdownTooltip = ({
  children,
  behaviorsByRun,
}: BehaviorsBreakdownTooltipProps) => {
  if (behaviorsByRun.length === 0 || behaviorsByRun[0].length === 0) {
    return <>{children}</>;
  }

  const isMultiRun = behaviorsByRun.length > 1;
  const behaviorNames = behaviorsByRun[0].map((b) => b.behavior_name);

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
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b text-muted-slate">
                <th className="px-3 py-1.5 text-left font-medium">Evaluator</th>
                {behaviorsByRun.map((_, runIdx) => (
                  <th
                    key={runIdx}
                    className="px-3 py-1.5 text-center font-medium"
                  >
                    {getColumnHeader(isMultiRun, runIdx)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {behaviorNames.map((name, bIdx) => (
                <tr key={bIdx} className="border-b last:border-b-0">
                  <td className="max-w-48 truncate px-3 py-1.5">{name}</td>
                  {behaviorsByRun.map((run, runIdx) => (
                    <td key={runIdx} className="px-3 py-1.5 text-center">
                      <PassedIcon passed={run[bIdx]?.passed ?? false} />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </TooltipContent>
      </TooltipPortal>
    </Tooltip>
  );
};

export default BehaviorsBreakdownTooltip;
