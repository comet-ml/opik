import React, { useCallback } from "react";
import { CellContext } from "@tanstack/react-table";
import { Copy } from "lucide-react";
import copy from "clipboard-copy";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import CellTooltipWrapper from "./CellTooltipWrapper";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { useToast } from "@/components/ui/use-toast";
import { cn } from "@/lib/utils";
import { getExperimentIconConfig } from "@/lib/experimentIcons";

type OpikConfigData = {
  experiment_id?: string | null;
  experiment_type?: string | null;
  assigned_variant?: string | null;
};

type CustomMeta<TData> = {
  callback?: (row: TData) => void;
};

type RowWithMetadata = {
  id: string;
  metadata?: Record<string, unknown>;
};

const TraceIdCell = <TData extends RowWithMetadata>(
  context: CellContext<TData, unknown>,
) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { callback } = (custom ?? {}) as CustomMeta<TData>;
  const value = context.getValue() as string;
  const { toast } = useToast();
  const row = context.row.original;

  const configData = row.metadata?.opik_config as OpikConfigData | undefined;
  const experimentId = configData?.experiment_id;

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
      if (callback) {
        callback(row);
      }
    },
    [callback, row],
  );

  const iconConfig = experimentId
    ? getExperimentIconConfig(
        configData?.experiment_type,
        configData?.assigned_variant,
      )
    : null;
  const Icon = iconConfig?.icon;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="group py-1"
    >
      {value ? (
        <TooltipWrapper content={value} stopClickPropagation>
          <div className="flex max-w-full items-center gap-2.5">
            {Icon && experimentId ? (
              <CellTooltipWrapper
                content={`${iconConfig.label}: ${experimentId}`}
              >
                <Icon
                  className="size-4 shrink-0"
                  style={{ color: iconConfig.color }}
                />
              </CellTooltipWrapper>
            ) : (
              <div className="size-4 shrink-0" />
            )}
            <Button
              variant="tableLink"
              size="sm"
              className={cn("block truncate px-0 leading-8")}
              onClick={handleClick}
            >
              {value}
            </Button>
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
      ) : (
        "-"
      )}
    </CellWrapper>
  );
};

export default TraceIdCell;
