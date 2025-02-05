import React, { useCallback, useState } from "react";
import { Download } from "lucide-react";
import FileSaver from "file-saver";
import { json2csv } from "json-2-csv";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

type ExportToButtonProps = {
  generateFileName: (extension: string) => string;
  getData: () => Array<object>;
  disabled: boolean;
};

const ExportToButton: React.FC<ExportToButtonProps> = ({
  generateFileName,
  getData,
  disabled,
}) => {
  const [open, setOpen] = useState<boolean>(false);

  const exportCSVHandler = useCallback(() => {
    const fileName = generateFileName("csv");
    const mappedRows = getData();

    FileSaver.saveAs(
      new Blob(
        [
          json2csv(mappedRows, {
            arrayIndexesAsKeys: true,
            escapeHeaderNestedDots: false,
          }),
        ],
        {
          type: "text/csv;charset=utf-8",
        },
      ),
      fileName,
    );
  }, [getData, generateFileName]);

  const exportJSONHandler = useCallback(() => {
    const fileName = generateFileName("json");
    const mappedRows = getData();

    FileSaver.saveAs(
      new Blob([JSON.stringify(mappedRows, null, 2)], {
        type: "application/json;charset=utf-8",
      }),
      fileName,
    );
  }, [getData, generateFileName]);
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
