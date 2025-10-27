import React, { useCallback, useState } from "react";
import { Download, Loader2 } from "lucide-react";
import FileSaver from "file-saver";
import { json2csv } from "json-2-csv";
import get from "lodash/get";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useToast } from "@/components/ui/use-toast";

type ExportToButtonProps = {
  generateFileName: (extension: string) => string;
  getData: () => Array<object> | Promise<Array<object>>;
  disabled: boolean;
};

const ExportToButton: React.FC<ExportToButtonProps> = ({
  generateFileName,
  getData,
  disabled,
}) => {
  const [open, setOpen] = useState<boolean>(false);
  const [loading, setLoading] = useState<boolean>(false);
  const { toast } = useToast();

  const handleExport = useCallback(
    async (exportFn: (data: Array<object>) => void) => {
      setLoading(true);
      try {
        const mappedRows = await Promise.resolve(getData());
        exportFn(mappedRows);
        setOpen(false);
      } catch (error) {
        const message = get(
          error,
          ["response", "data", "message"],
          get(error, "message", "Failed to fetch data for export"),
        );
        toast({
          title: "Export failed",
          description: message,
          variant: "destructive",
        });
      } finally {
        setLoading(false);
      }
    },
    [getData, toast],
  );

  const exportCSVHandler = useCallback(() => {
    handleExport((mappedRows: Array<object>) => {
      const fileName = generateFileName("csv");

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
    });
  }, [handleExport, generateFileName]);

  const exportJSONHandler = useCallback(() => {
    handleExport((mappedRows: Array<object>) => {
      const fileName = generateFileName("json");

      FileSaver.saveAs(
        new Blob([JSON.stringify(mappedRows, null, 2)], {
          type: "application/json;charset=utf-8",
        }),
        fileName,
      );
    });
  }, [handleExport, generateFileName]);
  return (
    <DropdownMenu open={open} onOpenChange={setOpen}>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="icon-sm" disabled={disabled || loading}>
          {loading ? <Loader2 className="animate-spin" /> : <Download />}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-52">
        <DropdownMenuItem onClick={exportCSVHandler} disabled={loading}>
          Export as CSV
        </DropdownMenuItem>
        <DropdownMenuItem onClick={exportJSONHandler} disabled={loading}>
          Export as JSON
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default ExportToButton;
