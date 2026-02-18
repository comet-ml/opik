import React from "react";
import { Check, X } from "lucide-react";

import { BehaviorResult } from "@/types/evaluation-suites";

type BehaviorsResultsTableProps = {
  behaviors: BehaviorResult[];
};

const BehaviorsResultsTable: React.FunctionComponent<
  BehaviorsResultsTableProps
> = ({ behaviors }) => {
  if (behaviors.length === 0) return null;

  return (
    <div className="mt-4">
      <h4 className="comet-body-s-accented mb-2">
        Expected behaviors ({behaviors.length})
      </h4>
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b text-muted-slate">
            <th className="pb-1.5 text-left font-medium">Behavior</th>
            <th className="w-16 pb-1.5 text-center font-medium">Passed</th>
            <th className="pb-1.5 text-left font-medium">Reason</th>
          </tr>
        </thead>
        <tbody>
          {behaviors.map((b, idx) => (
            <tr key={idx} className="border-b last:border-b-0">
              <td className="max-w-48 truncate py-1.5 pr-2">
                {b.behavior_name}
              </td>
              <td className="py-1.5 text-center">
                {b.passed ? (
                  <Check className="mx-auto size-4 text-green-600" />
                ) : (
                  <X className="mx-auto size-4 text-red-600" />
                )}
              </td>
              <td className="max-w-64 truncate py-1.5 text-muted-slate">
                {b.reason ?? "\u2014"}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

export default BehaviorsResultsTable;
