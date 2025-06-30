import { CellContext } from "@tanstack/react-table";
import { Button } from "@/components/ui/button";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { WorkspacePreference } from "@/components/pages/ConfigurationPage/WorkspacePreferencesTab/types";

type CustomMeta = {
  onEdit: (row: WorkspacePreference) => void;
};

const WorkspacePreferencesActionsCell: React.FunctionComponent<
  CellContext<WorkspacePreference, unknown>
> = (context) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { onEdit } = (custom ?? {}) as CustomMeta;
  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <Button
        size="sm"
        variant="ghost"
        onClick={() => onEdit(context.row.original)}
      >
        Edit
      </Button>
    </CellWrapper>
  );
};

export default WorkspacePreferencesActionsCell;
