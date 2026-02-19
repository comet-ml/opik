import { useState, useCallback, useMemo } from "react";
import { Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import DataTable from "@/components/shared/DataTable/DataTable";
import { convertColumnDataToColumn } from "@/lib/table";
import { generateActionsColumDef } from "@/components/shared/DataTable/utils";
import { ROW_HEIGHT } from "@/types/shared";
import {
  BehaviorDisplayRow,
  DEFAULT_EXECUTION_POLICY,
  ExecutionPolicy,
} from "@/types/evaluation-suites";
import {
  useAddBehavior,
  useEditBehavior,
  useDeleteBehavior,
  useAddedBehaviors,
  useEditedBehaviors,
  useDeletedBehaviorIds,
  useSetExecutionPolicy,
  useSetOriginalExecutionPolicy,
  useBehaviorsExecutionPolicy,
  useOriginalExecutionPolicy,
} from "@/store/EvaluationSuiteDraftStore";
import BehaviorActionsCell from "./BehaviorActionsCell";
import AddEditBehaviorDialog from "./AddEditBehaviorDialog";
import ExecutionPolicyDropdown from "./ExecutionPolicyDropdown";
import { BEHAVIOR_COLUMNS } from "./columns";
import { useBehaviorDisplayRows } from "./useBehaviorDisplayRows";

interface BehaviorsSectionProps {
  datasetId: string;
}

const BehaviorsSection: React.FC<BehaviorsSectionProps> = ({ datasetId }) => {
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editingBehavior, setEditingBehavior] =
    useState<BehaviorDisplayRow>();

  const addBehavior = useAddBehavior();
  const editBehavior = useEditBehavior();
  const deleteBehavior = useDeleteBehavior();
  const addedBehaviors = useAddedBehaviors();
  const editedBehaviors = useEditedBehaviors();
  const deletedBehaviorIds = useDeletedBehaviorIds();
  const setExecutionPolicy = useSetExecutionPolicy();
  const setOriginalExecutionPolicy = useSetOriginalExecutionPolicy();
  const executionPolicy = useBehaviorsExecutionPolicy();
  const originalExecutionPolicy = useOriginalExecutionPolicy();

  // TODO: Once OPIK-4224 lands, read evaluators from dataset version data
  // instead of this empty array placeholder.
  const serverEvaluators: never[] = [];

  const displayRows = useBehaviorDisplayRows(
    serverEvaluators,
    addedBehaviors,
    editedBehaviors,
    deletedBehaviorIds,
  );

  const currentPolicy = executionPolicy ?? DEFAULT_EXECUTION_POLICY;

  const handlePolicyChange = useCallback(
    (policy: ExecutionPolicy) => {
      if (!originalExecutionPolicy) {
        setOriginalExecutionPolicy(currentPolicy);
      }
      setExecutionPolicy(policy);
    },
    [
      currentPolicy,
      originalExecutionPolicy,
      setExecutionPolicy,
      setOriginalExecutionPolicy,
    ],
  );

  const handleEditSubmit = useCallback(
    (behavior: Omit<BehaviorDisplayRow, "id">) => {
      if (editingBehavior) {
        editBehavior(editingBehavior.id, behavior);
      }
    },
    [editingBehavior, editBehavior],
  );

  const openAddDialog = () => {
    setEditingBehavior(undefined);
    setDialogOpen(true);
  };

  const openEditDialog = useCallback((row: BehaviorDisplayRow) => {
    setEditingBehavior(row);
    setDialogOpen(true);
  }, []);

  const columns = useMemo(
    () => [
      ...convertColumnDataToColumn<BehaviorDisplayRow, BehaviorDisplayRow>(
        BEHAVIOR_COLUMNS,
        {},
      ),
      generateActionsColumDef({
        cell: BehaviorActionsCell,
        customMeta: { onEdit: openEditDialog, onDelete: deleteBehavior },
      }),
    ],
    [openEditDialog, deleteBehavior],
  );

  return (
    <div className="mb-6">
      <div className="mb-3 flex items-center justify-between">
        <div>
          <h2 className="comet-body-s-accented">
            Evaluation suite behaviors
          </h2>
          <p className="comet-body-xs text-muted-slate">
            Define behaviors that will be evaluated on all the items in the
            evaluation suite
          </p>
        </div>
        <ExecutionPolicyDropdown
          policy={currentPolicy}
          onChange={handlePolicyChange}
        />
      </div>

      {displayRows.length > 0 && (
        <div className="mb-3">
          <DataTable
            columns={columns}
            data={displayRows}
            getRowId={(row) => row.id}
            rowHeight={ROW_HEIGHT.small}
          />
        </div>
      )}

      <Button variant="outline" size="sm" onClick={openAddDialog}>
        <Plus className="mr-1 size-4" />
        Add new behavior
      </Button>

      <AddEditBehaviorDialog
        open={dialogOpen}
        setOpen={setDialogOpen}
        behavior={editingBehavior}
        onSubmit={editingBehavior ? handleEditSubmit : addBehavior}
      />
    </div>
  );
};

export default BehaviorsSection;
