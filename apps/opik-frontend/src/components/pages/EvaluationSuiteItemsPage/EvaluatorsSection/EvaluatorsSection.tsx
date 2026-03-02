import React, { useState, useCallback, useMemo } from "react";
import { Trash } from "lucide-react";
import { ColumnPinningState, RowSelectionState } from "@tanstack/react-table";
import { Button } from "@/components/ui/button";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import ExplainerCallout from "@/components/shared/ExplainerCallout/ExplainerCallout";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Separator } from "@/components/ui/separator";
import { convertColumnDataToColumn } from "@/lib/table";
import {
  generateActionsColumDef,
  generateSelectColumDef,
} from "@/components/shared/DataTable/utils";
import { COLUMN_NAME_ID, COLUMN_SELECT_ID, ROW_HEIGHT } from "@/types/shared";
import {
  EvaluatorDisplayRow,
  DEFAULT_EXECUTION_POLICY,
  ExecutionPolicy,
} from "@/types/evaluation-suites";
import { Evaluator } from "@/types/datasets";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import {
  useAddEvaluator,
  useEditEvaluator,
  useDeleteEvaluator,
  useAddedEvaluators,
  useEditedEvaluators,
  useDeletedEvaluatorIds,
  useSetExecutionPolicy,
  useEvaluatorsExecutionPolicy,
} from "@/store/EvaluationSuiteDraftStore";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import EvaluatorActionsCell from "./EvaluatorActionsCell";
import AddEditEvaluatorDialog from "./AddEditEvaluatorDialog";
import ExecutionPolicyDropdown from "./ExecutionPolicyDropdown";
import { EVALUATOR_COLUMNS } from "./columns";
import { useEvaluatorDisplayRows } from "./useEvaluatorDisplayRows";

interface EvaluatorsSectionProps {
  serverEvaluators: Evaluator[];
  serverExecutionPolicy?: ExecutionPolicy;
}

const COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID, COLUMN_NAME_ID],
  right: [],
};

const EvaluatorsSection: React.FC<EvaluatorsSectionProps> = ({
  serverEvaluators,
  serverExecutionPolicy,
}) => {
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editingEvaluator, setEditingEvaluator] =
    useState<EvaluatorDisplayRow>();
  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});
  const [bulkDeleteOpen, setBulkDeleteOpen] = useState(false);

  const addEvaluator = useAddEvaluator();
  const editEvaluator = useEditEvaluator();
  const deleteEvaluator = useDeleteEvaluator();
  const addedEvaluators = useAddedEvaluators();
  const editedEvaluators = useEditedEvaluators();
  const deletedEvaluatorIds = useDeletedEvaluatorIds();
  const setExecutionPolicy = useSetExecutionPolicy();
  const draftPolicy = useEvaluatorsExecutionPolicy();

  const displayRows = useEvaluatorDisplayRows(
    serverEvaluators,
    addedEvaluators,
    editedEvaluators,
    deletedEvaluatorIds,
  );

  const currentPolicy =
    draftPolicy ?? serverExecutionPolicy ?? DEFAULT_EXECUTION_POLICY;

  const handleEditSubmit = useCallback(
    (evaluator: Omit<EvaluatorDisplayRow, "id">) => {
      if (editingEvaluator) {
        editEvaluator(editingEvaluator.id, evaluator);
      }
    },
    [editingEvaluator, editEvaluator],
  );

  const openAddDialog = useCallback(() => {
    setEditingEvaluator(undefined);
    setDialogOpen(true);
  }, []);

  const openEditDialog = useCallback((row: EvaluatorDisplayRow) => {
    setEditingEvaluator(row);
    setDialogOpen(true);
  }, []);

  const selectedRows = useMemo(
    () => displayRows.filter((row) => rowSelection[row.id]),
    [displayRows, rowSelection],
  );

  const handleBulkDelete = useCallback(() => {
    selectedRows.forEach((row) => deleteEvaluator(row.id));
    setRowSelection({});
  }, [selectedRows, deleteEvaluator]);

  const columns = useMemo(
    () => [
      generateSelectColumDef<EvaluatorDisplayRow>(),
      ...convertColumnDataToColumn<EvaluatorDisplayRow, EvaluatorDisplayRow>(
        EVALUATOR_COLUMNS,
        {},
      ),
      generateActionsColumDef({
        cell: EvaluatorActionsCell,
        customMeta: { onEdit: openEditDialog, onDelete: deleteEvaluator },
      }),
    ],
    [openEditDialog, deleteEvaluator],
  );

  return (
    <div className="mb-6">
      <ExplainerCallout
        className="mb-4"
        {...EXPLAINERS_MAP[EXPLAINER_ID.what_are_evaluation_suite_evaluators]}
      />

      <div className="mb-4 flex w-full items-center justify-between">
        <ExecutionPolicyDropdown
          policy={currentPolicy}
          onChange={setExecutionPolicy}
        />
        <div className="flex items-center gap-2">
          <TooltipWrapper content="Delete">
            <Button
              variant="outline"
              size="icon-sm"
              onClick={() => setBulkDeleteOpen(true)}
              disabled={selectedRows.length === 0}
            >
              <Trash />
            </Button>
          </TooltipWrapper>
          <ConfirmDialog
            open={bulkDeleteOpen}
            setOpen={setBulkDeleteOpen}
            onConfirm={handleBulkDelete}
            title="Delete evaluators"
            description={`Are you sure you want to delete ${
              selectedRows.length
            } evaluator${
              selectedRows.length !== 1 ? "s" : ""
            }? This action can't be undone.`}
            confirmText="Delete evaluators"
            confirmButtonVariant="destructive"
          />
          <Separator orientation="vertical" className="mx-2 h-4" />
          <Button size="sm" onClick={openAddDialog}>
            Add new evaluator
          </Button>
        </div>
      </div>

      <DataTable
        columns={columns}
        data={displayRows}
        getRowId={(row) => row.id}
        rowHeight={ROW_HEIGHT.small}
        columnPinning={COLUMN_PINNING}
        selectionConfig={{ rowSelection, setRowSelection }}
        noData={
          <DataTableNoData title="There are no evaluators yet">
            <Button variant="link" onClick={openAddDialog}>
              Create new evaluator
            </Button>
          </DataTableNoData>
        }
      />

      <AddEditEvaluatorDialog
        open={dialogOpen}
        setOpen={setDialogOpen}
        evaluator={editingEvaluator}
        onSubmit={editingEvaluator ? handleEditSubmit : addEvaluator}
        existingNames={displayRows.map((r) => r.name)}
      />
    </div>
  );
};

export default EvaluatorsSection;
