import React, { useCallback } from "react";
import { CellContext } from "@tanstack/react-table";
import { Copy } from "lucide-react";
import copy from "clipboard-copy";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { useToast } from "@/components/ui/use-toast";
import { cn } from "@/lib/utils";

type CustomMeta<TData> = {
  callback: (row: TData) => void;
  asId: boolean;
  tooltip?: string;
};

const LinkCell = <TData,>(context: CellContext<TData, unknown>) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { callback, asId, tooltip } = (custom ?? {}) as CustomMeta<TData>;
  const value = context.getValue() as string;
  const { toast } = useToast();

  const copyClickHandler = useCallback(
    (event: React.MouseEvent<HTMLButtonElement>) => {
      event.stopPropagation();
      toast({
        description: "ID copied to clipboard",
      });
      copy(value);
    },
    [toast, value],
  );

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="group py-1"
    >
      {value ? (
        <TooltipWrapper content={tooltip ?? value} stopClickPropagation>
          <div className="flex max-w-full items-center">
            <Button
              variant="tableLink"
              size="sm"
              className="block truncate px-0 leading-8"
              onClick={(event) => {
                event.stopPropagation();
                callback(context.row.original);
              }}
            >
              {value}
            </Button>
            <Button
              size="icon-xs"
              variant="ghost"
              className={cn("hidden", asId && "group-hover:inline-flex")}
              onClick={copyClickHandler}
            >
              <Copy />
            </Button>
          </div>
        </TooltipWrapper>
      ) : (
        "-"
      )}
    </CellWrapper>
  );
};

export default LinkCell;
