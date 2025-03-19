import React, { useCallback } from "react";
import { CellContext } from "@tanstack/react-table";
import { Copy } from "lucide-react";
import copy from "clipboard-copy";

import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { useToast } from "@/components/ui/use-toast";
import { Button } from "@/components/ui/button";

const IdCell = (context: CellContext<unknown, string>) => {
  const value = context.getValue();
  const { toast } = useToast();

  const copyClickHandler = useCallback(
    (event: React.MouseEvent<HTMLButtonElement>) => {
      event.stopPropagation();
      toast({
        description: "ID copied to clipboard",
      });
      copy(value);
    },
    [value, toast],
  );

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="group"
    >
      <TooltipWrapper content={value} stopClickPropagation>
        <div className="flex max-w-full items-center">
          <div className="truncate">{value}</div>
          <Button
            size="icon-xs"
            variant="ghost"
            className="hidden group-hover:inline-flex"
            onClick={copyClickHandler}
          >
            <Copy />
          </Button>
        </div>
      </TooltipWrapper>
    </CellWrapper>
  );
};

export default IdCell;
