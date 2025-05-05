import React from "react";
import { CellContext } from "@tanstack/react-table";

import { ExperimentItem, ExperimentsCompare } from "@/types/datasets";
import VerticallySplitCellWrapper, {
  CustomMeta,
} from "@/components/pages-shared/experiments/VerticallySplitCellWrapper/VerticallySplitCellWrapper";
import ResourceLink, {
  RESOURCE_TYPE,
} from "@/components/shared/ResourceLink/ResourceLink";

const CompareExperimentsNameCell: React.FC<
  CellContext<ExperimentsCompare, unknown>
> = (context) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { experiments = [] } = (custom ?? {}) as CustomMeta;
  const experimentCompare = context.row.original;

  const renderContent = (
    item: ExperimentItem | undefined,
    experimentId: string,
  ) => {
    const experiment = experiments.find((e) => e.id === experimentId);

    return (
      <div className="-mt-2 h-8">
        <ResourceLink
          id={experiment?.dataset_id || ""}
          name={experiment?.name}
          resource={RESOURCE_TYPE.experiment}
          search={{
            experiments: [experimentId],
          }}
        />
      </div>
    );
  };

  return (
    <VerticallySplitCellWrapper
      renderContent={renderContent}
      experimentCompare={experimentCompare}
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      rowId={context.row.id}
    />
  );
};

export default CompareExperimentsNameCell;
