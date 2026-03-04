import React from "react";
import { Check, X } from "lucide-react";

import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { AssertionResult } from "@/types/datasets";

type AssertionResultsTableProps = {
  assertions: AssertionResult[];
};

export const AssertionResultsTable: React.FC<AssertionResultsTableProps> = ({
  assertions,
}) => {
  if (assertions.length === 0) return null;

  return (
    <div className="mt-4">
      <h4 className="comet-body-s-accented mb-2">
        Assertions ({assertions.length})
      </h4>
      <Table className="text-sm">
        <TableHeader>
          <TableRow className="text-muted-slate">
            <TableHead className="pb-1.5 text-left font-medium">
              Assertion
            </TableHead>
            <TableHead className="w-16 pb-1.5 text-center font-medium">
              Passed
            </TableHead>
            <TableHead className="pb-1.5 text-left font-medium">
              Reason
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {assertions.map((a, idx) => (
            <TableRow key={idx}>
              <TableCell className="max-w-48 truncate py-1.5 pr-2">
                {a.name}
              </TableCell>
              <TableCell className="py-1.5 text-center">
                {a.passed ? (
                  <Check className="mx-auto size-4 text-green-600" />
                ) : (
                  <X className="mx-auto size-4 text-red-600" />
                )}
              </TableCell>
              <TableCell className="max-w-64 truncate py-1.5 text-muted-slate">
                {a.reason ?? "\u2014"}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
};

export default AssertionResultsTable;
