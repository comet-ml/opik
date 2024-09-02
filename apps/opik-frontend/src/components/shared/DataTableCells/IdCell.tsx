import React, { useCallback } from "react";
import truncate from "lodash/truncate";
import { CellContext } from "@tanstack/react-table";
import { Copy } from "lucide-react";
import copy from "clipboard-copy";

import { Tag } from "@/components/ui/tag";
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
      className="px-1 py-1.5"
    >
      <TooltipWrapper content={value}>
        <Tag size="lg" variant="gray" className="flex items-center">
          {truncate(value, { length: 9 })}
          <Button size="icon-xs" variant="ghost" onClick={copyClickHandler}>
            <Copy className="size-3.5" />
          </Button>
        </Tag>
      </TooltipWrapper>
    </CellWrapper>
  );
};

export default IdCell;
