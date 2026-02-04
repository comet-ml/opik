import React from "react";
import { Link } from "@tanstack/react-router";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { Experiment, EXPERIMENT_TYPE } from "@/types/datasets";
import useAppStore from "@/store/AppStore";
import { cn } from "@/lib/utils";
import { getExperimentIconConfig } from "@/lib/experimentIcons";

const ExperimentNameCell = <TData extends Experiment>(
  context: CellContext<TData, unknown>
) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const experiment = context.row.original;
  const { name, id, dataset_id, type } = experiment;

  const isLiveExperiment =
    type === EXPERIMENT_TYPE.LIVE ||
    type === EXPERIMENT_TYPE.AB ||
    type === EXPERIMENT_TYPE.OPTIMIZER;

  const linkTo = isLiveExperiment
    ? "/$workspaceName/experiments/live/$experimentId"
    : "/$workspaceName/experiments/$datasetId/compare";

  const linkParams = isLiveExperiment
    ? { workspaceName, experimentId: id }
    : { workspaceName, datasetId: dataset_id! };

  const linkSearch = isLiveExperiment ? { name } : { experiments: [id] };

  const iconConfig = getExperimentIconConfig(type);
  const Icon = iconConfig.icon;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="py-1.5"
    >
      <Link
        to={linkTo}
        params={linkParams}
        search={linkSearch}
        onClick={(e) => e.stopPropagation()}
        className={cn(
          "flex items-center gap-1.5 overflow-hidden",
          "text-foreground hover:underline"
        )}
      >
        <Icon
          className="size-3.5 shrink-0"
          style={{ color: iconConfig.color }}
        />
        <span className="truncate">{name || id}</span>
      </Link>
    </CellWrapper>
  );
};

export default ExperimentNameCell;
