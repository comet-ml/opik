import React, { useCallback } from "react";
import { CellContext } from "@tanstack/react-table";
import { useNavigate } from "@tanstack/react-router";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import useAppStore from "@/store/AppStore";
import { ExperimentReference } from "@/types/traces";

const ExperimentCell = <TData,>(context: CellContext<TData, unknown>) => {
  const value = context.getValue() as ExperimentReference | undefined;
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const handleClick = useCallback(
    (event: React.MouseEvent<HTMLButtonElement>) => {
      event.stopPropagation();
      if (value?.name) {
        navigate({
          to: "/$workspaceName/experiments",
          params: {
            workspaceName,
          },
          search: {
            search: value.name,
          },
        });
      }
    },
    [navigate, workspaceName, value?.name],
  );

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="group py-1"
    >
      {value?.name ? (
        <TooltipWrapper content={value.name} stopClickPropagation>
          <div className="flex max-w-full items-center">
            <Button
              variant="tableLink"
              size="sm"
              className="block truncate px-0 leading-8"
              onClick={handleClick}
            >
              {value.name}
            </Button>
          </div>
        </TooltipWrapper>
      ) : (
        "-"
      )}
    </CellWrapper>
  );
};

export default ExperimentCell;
