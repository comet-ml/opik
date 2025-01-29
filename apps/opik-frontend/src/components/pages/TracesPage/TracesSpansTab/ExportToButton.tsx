import React, { useCallback, useState } from "react";
import { Download } from "lucide-react";
import FileSaver from "file-saver";
import { json2csv } from "json-2-csv";
import last from "lodash/last";
import first from "lodash/first";
import get from "lodash/get";
import slugify from "slugify";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Span, Trace } from "@/types/traces";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";

const mapRowData = (rows: Array<Trace | Span>, columns: string[]) => {
  return rows.map((row) => {
    return columns.reduce<Record<string, unknown>>((acc, column) => {
      // we need split by dot to parse usage into correct structure
      const keys = column.split(".");
      const key = last(keys) as string;
      const keyPrefix = first(keys) as string;

      if (keyPrefix === "feedback_scores") {
        acc[key] = get(
          row.feedback_scores?.find((f) => f.name === key),
          "value",
          "-",
        );
      } else {
        acc[key] = get(row, keys, "");
      }
      return acc;
    }, {});
  });
};

const generateFileName = (
  projectName: string,
  type: TRACE_DATA_TYPE,
  extension = "csv",
) => {
  return `${slugify(projectName, { lower: true })}-${
    type === TRACE_DATA_TYPE.traces ? "traces" : "llm-calls"
  }.${extension}`;
};

type ExportToButtonProps = {
  projectName: string;
  type: TRACE_DATA_TYPE;
  disabled: boolean;
  rows: Array<Trace | Span>;
  columnsToExport: string[];
};

const ExportToButton: React.FC<ExportToButtonProps> = ({
  projectName,
  type,
  disabled,
  rows,
  columnsToExport,
}) => {
  const [open, setOpen] = useState<boolean>(false);

  const exportCSVHandler = useCallback(() => {
    const fileName = generateFileName(projectName, type);
    const mappedRows = mapRowData(rows, columnsToExport);

    FileSaver.saveAs(
      new Blob([json2csv(mappedRows, { arrayIndexesAsKeys: true })], {
        type: "text/csv;charset=utf-8",
      }),
      fileName,
    );
  }, [projectName, rows, columnsToExport, type]);

  const exportJSONHandler = useCallback(() => {
    const fileName = generateFileName(projectName, type, "json");
    const mappedRows = mapRowData(rows, columnsToExport);

    FileSaver.saveAs(
      new Blob([JSON.stringify(mappedRows, null, 2)], {
        type: "application/json;charset=utf-8",
      }),
      fileName,
    );
  }, [projectName, rows, columnsToExport, type]);
  return (
    <DropdownMenu open={open} onOpenChange={setOpen}>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="icon" disabled={disabled}>
          <Download className="size-4" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-52">
        <DropdownMenuItem onClick={exportCSVHandler}>
          Export as CSV
        </DropdownMenuItem>
        <DropdownMenuItem onClick={exportJSONHandler}>
          Export as JSON
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default ExportToButton;
