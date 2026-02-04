import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import CellTooltipWrapper from "./CellTooltipWrapper";
import { getExperimentIconConfig } from "@/lib/experimentIcons";

type OpikConfigData = {
  experiment_id?: string | null;
  experiment_type?: string | null;
  assigned_variant?: string | null;
  values?: Record<string, unknown>;
};

const ExperimentCell = <TData,>(context: CellContext<TData, object>) => {
  const metadata = context.getValue() as Record<string, unknown> | undefined;
  const configData = metadata?.opik_config as OpikConfigData | undefined;
  const experimentId = configData?.experiment_id;

  if (!experimentId) return null;

  const iconConfig = getExperimentIconConfig(
    configData?.experiment_type,
    configData?.assigned_variant
  );
  const Icon = iconConfig.icon;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <CellTooltipWrapper content={`${iconConfig.label}: ${experimentId}`}>
        <Icon className="size-4" style={{ color: iconConfig.color }} />
      </CellTooltipWrapper>
    </CellWrapper>
  );
};

export default ExperimentCell;
