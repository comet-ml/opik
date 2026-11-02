import React from "react";

import { coerceNumericRows, VegaRows, VegaSpec } from "@/lib/charts/vega";
import { cn } from "@/lib/utils";

/** A table "chart" is stored as `{"table": {"columns": [...]}}` in place of a Vega-Lite spec. */
export const tableColumns = (spec: VegaSpec): string[] | null => {
  const table = (spec as { table?: { columns?: unknown } }).table;
  return table && Array.isArray(table.columns)
    ? table.columns.map(String)
    : null;
};

const formatCell = (value: unknown): string => {
  if (value === null || value === undefined || value === "") return "—";
  if (typeof value === "number") {
    return value.toLocaleString(undefined, { maximumFractionDigits: 2 });
  }
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
};

type RowsTableProps = {
  rows: VegaRows;
  columns?: string[] | null;
  note?: string;
};

const RowsTable: React.FunctionComponent<RowsTableProps> = ({
  rows,
  columns,
  note,
}) => {
  // A widget's rows come straight from the query, where numbers are often strings.
  const data = coerceNumericRows(rows);
  const keys = columns?.length ? columns : Object.keys(data[0] ?? {});
  return (
    <div className="flex size-full flex-col gap-1">
      {note && <span className="comet-body-xs text-muted-slate">{note}</span>}
      <div className="min-h-0 flex-1 overflow-auto">
        <table className="comet-body-xs w-full border-collapse">
          <thead className="sticky top-0 bg-background">
            <tr>
              {keys.map((key) => (
                <th
                  key={key}
                  className="whitespace-nowrap border-b px-2 py-1 text-left font-medium text-muted-slate"
                >
                  {key}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {data.map((row, index) => (
              <tr key={index} className="border-b last:border-0">
                {keys.map((key) => (
                  <td
                    key={key}
                    className={cn(
                      "whitespace-nowrap px-2 py-1",
                      typeof row[key] === "number" && "text-right tabular-nums",
                    )}
                  >
                    {formatCell(row[key])}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export default RowsTable;
