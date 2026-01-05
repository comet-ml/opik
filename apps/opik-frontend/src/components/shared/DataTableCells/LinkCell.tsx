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
  callback?: (row: TData) => void;
  asId?: boolean;
  tooltip?: string;
  getIsDisabled?: (row: TData) => boolean;
  disabledTooltip?: string;
};

const LinkCell = <TData,>(context: CellContext<TData, unknown>) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { callback, asId, tooltip, getIsDisabled, disabledTooltip } = (custom ??
    {}) as CustomMeta<TData>;
  const value = context.getValue() as string | number;
  const { toast } = useToast();
  const row = context.row.original;

  // Check if disabled: value is 0/falsy OR getIsDisabled returns true
  // Note: When value is 0, we render "-" but isDisabled is still true for consistency
  const isDisabled = !value || (getIsDisabled ? getIsDisabled(row) : false);
  const effectiveTooltip = isDisabled
    ? disabledTooltip ?? tooltip ?? String(value)
    : tooltip ?? String(value);

  const copyClickHandler = useCallback(
    (event: React.MouseEvent<HTMLButtonElement>) => {
      event.stopPropagation();
      toast({
        description: "ID copied to clipboard",
      });
      copy(String(value));
    },
    [toast, value],
  );

  const handleClick = useCallback(
    (event: React.MouseEvent<HTMLButtonElement>) => {
      event.stopPropagation();
      if (!isDisabled && callback) {
        callback(row);
      }
    },
    [isDisabled, callback, row],
  );

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="group py-1"
    >
      {value ? (
        <TooltipWrapper content={effectiveTooltip} stopClickPropagation>
          <div className="flex max-w-full items-center">
            <Button
              variant="tableLink"
              size="sm"
              className={cn(
                "block truncate px-0 leading-8",
                isDisabled && "cursor-not-allowed opacity-50",
              )}
              onClick={handleClick}
              disabled={isDisabled}
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
