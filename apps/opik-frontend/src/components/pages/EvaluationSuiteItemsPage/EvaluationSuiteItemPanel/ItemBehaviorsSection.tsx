import { useCallback, useMemo, useState } from "react";
import { Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import DataTable from "@/components/shared/DataTable/DataTable";
import { convertColumnDataToColumn } from "@/lib/table";
import { generateActionsColumDef } from "@/components/shared/DataTable/utils";
import { ROW_HEIGHT } from "@/types/shared";
import { BehaviorDisplayRow } from "@/types/evaluation-suites";
import {
  useAddedBehaviors,
  useEditedBehaviors,
  useDeletedBehaviorIds,
  useItemAddedBehaviors,
  useItemEditedBehaviors,
  useItemDeletedBehaviorIds,
  useAddItemBehavior,
  useEditItemBehavior,
  useDeleteItemBehavior,
} from "@/store/EvaluationSuiteDraftStore";
import BehaviorActionsCell from "@/components/pages/EvaluationSuiteItemsPage/BehaviorsSection/BehaviorActionsCell";
import AddEditBehaviorDialog from "@/components/pages/EvaluationSuiteItemsPage/BehaviorsSection/AddEditBehaviorDialog";
import { BEHAVIOR_COLUMNS } from "@/components/pages/EvaluationSuiteItemsPage/BehaviorsSection/columns";
import { useBehaviorDisplayRows } from "@/components/pages/EvaluationSuiteItemsPage/BehaviorsSection/useBehaviorDisplayRows";

interface ItemBehaviorsSectionProps {
  itemId: string;
  /** Will be used once OPIK-4225 lands to read item evaluators */
  datasetId: string;
}

const EMPTY_EVALUATORS: never[] = [];

const ItemBehaviorsSection: React.FC<ItemBehaviorsSectionProps> = ({
  itemId,
}) => {
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editingBehavior, setEditingBehavior] =
    useState<BehaviorDisplayRow>();

  // Suite-level behaviors (read-only display)
  const suiteAddedBehaviors = useAddedBehaviors();
  const suiteEditedBehaviors = useEditedBehaviors();
  const suiteDeletedBehaviorIds = useDeletedBehaviorIds();

  // TODO: Once OPIK-4224 lands, read evaluators from dataset version data
  const suiteDisplayRows = useBehaviorDisplayRows(
    EMPTY_EVALUATORS,
    suiteAddedBehaviors,
    suiteEditedBehaviors,
    suiteDeletedBehaviorIds,
  );

  // Item-level behaviors (editable)
  const addItemBehavior = useAddItemBehavior();
  const editItemBehavior = useEditItemBehavior();
  const deleteItemBehavior = useDeleteItemBehavior();
  const itemAddedBehaviors = useItemAddedBehaviors(itemId);
  const itemEditedBehaviors = useItemEditedBehaviors(itemId);
  const itemDeletedBehaviorIds = useItemDeletedBehaviorIds(itemId);

  // TODO: Once OPIK-4225 lands, read item evaluators from dataset item data
  const itemDisplayRows = useBehaviorDisplayRows(
    EMPTY_EVALUATORS,
    itemAddedBehaviors,
    itemEditedBehaviors,
    itemDeletedBehaviorIds,
  );

  const handleAddSubmit = useCallback(
    (behavior: Omit<BehaviorDisplayRow, "id">) => {
      addItemBehavior(itemId, behavior);
    },
    [itemId, addItemBehavior],
  );

  const handleEditSubmit = useCallback(
    (behavior: Omit<BehaviorDisplayRow, "id">) => {
      if (editingBehavior) {
        editItemBehavior(itemId, editingBehavior.id, behavior);
      }
    },
    [itemId, editingBehavior, editItemBehavior],
  );

  const openAddDialog = () => {
    setEditingBehavior(undefined);
    setDialogOpen(true);
  };

  const openEditDialog = useCallback((row: BehaviorDisplayRow) => {
    setEditingBehavior(row);
    setDialogOpen(true);
  }, []);

  const handleDelete = useCallback(
    (id: string) => {
      deleteItemBehavior(itemId, id);
    },
    [itemId, deleteItemBehavior],
  );

  const suiteColumns = useMemo(
    () =>
      convertColumnDataToColumn<BehaviorDisplayRow, BehaviorDisplayRow>(
        BEHAVIOR_COLUMNS,
        {},
      ),
    [],
  );

  const itemColumns = useMemo(
    () => [
      ...convertColumnDataToColumn<BehaviorDisplayRow, BehaviorDisplayRow>(
        BEHAVIOR_COLUMNS,
        {},
      ),
      generateActionsColumDef({
        cell: BehaviorActionsCell,
        customMeta: { onEdit: openEditDialog, onDelete: handleDelete },
      }),
    ],
    [openEditDialog, handleDelete],
  );

  return (
    <div>
      <h3 className="comet-body-s-accented mb-2">Behaviors</h3>

      {/* Suite behaviors (read-only) */}
      {suiteDisplayRows.length > 0 && (
        <div className="mb-3">
          <p className="comet-body-xs mb-1 text-muted-slate">
            Suite behaviors (inherited)
          </p>
          <div className="opacity-60">
            <DataTable
              columns={suiteColumns}
              data={suiteDisplayRows}
              getRowId={(row) => row.id}
              rowHeight={ROW_HEIGHT.small}
            />
          </div>
        </div>
      )}

      {/* Item behaviors (editable) */}
      <p className="comet-body-xs mb-1 text-muted-slate">
        Item-specific behaviors
      </p>
      {itemDisplayRows.length > 0 && (
        <div className="mb-3">
          <DataTable
            columns={itemColumns}
            data={itemDisplayRows}
            getRowId={(row) => row.id}
            rowHeight={ROW_HEIGHT.small}
          />
        </div>
      )}

      <Button variant="outline" size="sm" onClick={openAddDialog}>
        <Plus className="mr-1 size-4" />
        Add item behavior
      </Button>

      <AddEditBehaviorDialog
        open={dialogOpen}
        setOpen={setDialogOpen}
        behavior={editingBehavior}
        onSubmit={editingBehavior ? handleEditSubmit : handleAddSubmit}
      />
    </div>
  );
};

export default ItemBehaviorsSection;
