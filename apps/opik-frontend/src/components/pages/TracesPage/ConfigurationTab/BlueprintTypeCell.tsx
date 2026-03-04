import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { Tag } from "@/components/ui/tag";
import { EnrichedBlueprintValue } from "@/types/agent-configs";

const BlueprintTypeCell = (context: CellContext<EnrichedBlueprintValue, string>) => {
  const value = context.getValue();

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <Tag size="md" variant="gray" className="capitalize">{value}</Tag>
    </CellWrapper>
  );
};

export default BlueprintTypeCell;
