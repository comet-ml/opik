import { CellContext } from "@tanstack/react-table";
import { FlaskConical } from "lucide-react";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import CellTooltipWrapper from "./CellTooltipWrapper";

type OpikConfigData = {
  experiment_id?: string | null;
  values?: Record<string, unknown>;
};

const ExperimentCell = <TData,>(context: CellContext<TData, object>) => {
  const metadata = context.getValue() as Record<string, unknown> | undefined;
  const configData = metadata?.opik_config as OpikConfigData | undefined;
  const experimentId = configData?.experiment_id;

  if (!experimentId) return null;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <CellTooltipWrapper content={`Experiment: ${experimentId}`}>
        <FlaskConical className="size-4 text-purple-500" />
      </CellTooltipWrapper>
    </CellWrapper>
  );
};

export default ExperimentCell;
