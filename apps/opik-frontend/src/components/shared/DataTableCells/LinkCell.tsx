import React, { useCallback } from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { Button } from "@/components/ui/button";
import truncate from "lodash/truncate";
import { Copy } from "lucide-react";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import copy from "clipboard-copy";
import { useToast } from "@/components/ui/use-toast";
import { cn } from "@/lib/utils";

type CustomMeta<TData> = {
  callback: (row: TData) => void;
  asId: boolean;
};

const LinkCell = <TData,>(context: CellContext<TData, unknown>) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { callback, asId } = (custom ?? {}) as CustomMeta<TData>;
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
      className="group"
    >
      <TooltipWrapper content={value}>
        <div className="flex max-w-full items-center">
          <Button
            variant="tableLink"
            size="sm"
            className="block truncate px-0 leading-8"
            onClick={() => callback(context.row.original)}
          >
            {asId ? truncate(value, { length: 9 }) : value}
          </Button>
          <Button
            size="icon-xs"
            variant="ghost"
            className={cn("hidden", asId && "group-hover:inline-flex")}
            onClick={copyClickHandler}
          >
            <Copy className="size-3.5" />
          </Button>
        </div>
      </TooltipWrapper>
    </CellWrapper>
  );
};

export default LinkCell;
