import React, { useCallback, useState } from "react";
import { Pencil, Plus, Trash } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { cn } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import {
  EvaluatorDisplayRow,
  LLMJudgeConfig,
  MetricType,
  METRIC_TYPE_LABELS,
} from "@/types/evaluation-suites";
import { Evaluator } from "@/types/datasets";
import {
  useItemAddedEvaluators,
  useItemEditedEvaluators,
  useItemDeletedEvaluatorIds,
  useAddItemEvaluator,
  useEditItemEvaluator,
  useDeleteItemEvaluator,
} from "@/store/EvaluationSuiteDraftStore";
import AddEditEvaluatorDialog from "@/components/pages/EvaluationSuiteItemsPage/EvaluatorsSection/AddEditEvaluatorDialog";
import { useEvaluatorDisplayRows } from "@/components/pages/EvaluationSuiteItemsPage/EvaluatorsSection/useEvaluatorDisplayRows";
import {
  formatEvaluatorConfig,
  getMetricIcon,
  getSectionLabel,
} from "@/lib/evaluator-converters";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";

const EMPTY_EVALUATORS: Evaluator[] = [];
const MAX_VISIBLE_ASSERTIONS = 3;

function getConfigSummary(row: EvaluatorDisplayRow): string | null {
  const summary = formatEvaluatorConfig(row.type, row.config);
  return summary || null;
}

function getDraftBorderClass(row: EvaluatorDisplayRow): string {
  if (row.isNew) return "border-l-2 border-l-green-500";
  if (row.isEdited) return "border-l-2 border-l-amber-500";
  return "";
}

interface AssertionsListProps {
  assertions: string[];
}

const AssertionsList: React.FC<AssertionsListProps> = ({ assertions }) => {
  if (!assertions.length) return null;

  const visible = assertions.slice(0, MAX_VISIBLE_ASSERTIONS);
  const hiddenCount = assertions.length - MAX_VISIBLE_ASSERTIONS;

  return (
    <div className="comet-body-s flex flex-col gap-1">
      {visible.map((assertion, index) => (
        <div key={index} className="rounded bg-foreground/[0.03] px-2 py-1">
          <TooltipWrapper
            content={assertion.length > 80 ? assertion : undefined}
          >
            <span className="line-clamp-2 text-foreground">{assertion}</span>
          </TooltipWrapper>
        </div>
      ))}
      {hiddenCount > 0 && (
        <span className="text-muted-slate">
          +{hiddenCount} more assertion{hiddenCount !== 1 ? "s" : ""}
        </span>
      )}
    </div>
  );
};

interface EvaluatorCardProps {
  row: EvaluatorDisplayRow;
  onEdit: (row: EvaluatorDisplayRow) => void;
  onDelete: (id: string) => void;
}

const EvaluatorCard: React.FC<EvaluatorCardProps> = ({
  row,
  onEdit,
  onDelete,
}) => {
  const [confirmOpen, setConfirmOpen] = useState(false);
  const configSummary = getConfigSummary(row);
  const isLLMJudge = row.type === MetricType.LLM_AS_JUDGE;
  const assertions = isLLMJudge
    ? (row.config as LLMJudgeConfig).assertions ?? []
    : [];
  const Icon = getMetricIcon(row.type);

  return (
    <>
      <Card className={cn("group p-3 shadow-none", getDraftBorderClass(row))}>
        <CardContent className="flex flex-col gap-1.5 p-0">
          {/* Zone A: Header */}
          <div className="flex items-center justify-between gap-2">
            <div className="flex min-w-0 items-center gap-2">
              <TooltipWrapper content={METRIC_TYPE_LABELS[row.type]}>
                <div className="flex size-6 shrink-0 items-center justify-center rounded bg-foreground/5">
                  <Icon className="size-3.5 text-muted-slate" />
                </div>
              </TooltipWrapper>
              <TooltipWrapper content={row.name}>
                <span className="comet-body-s-accented min-w-0 truncate">
                  {row.name}
                </span>
              </TooltipWrapper>
            </div>
            <div className="flex shrink-0 items-center gap-1.5 opacity-0 transition-opacity duration-150 group-focus-within:opacity-100 group-hover:opacity-100">
              <Button
                variant="outline"
                size="icon-xs"
                onClick={() => onEdit(row)}
                aria-label="Edit evaluator"
              >
                <Pencil className="size-3.5" />
              </Button>
              <Button
                variant="outline"
                size="icon-xs"
                className="hover:bg-destructive/10 hover:text-destructive"
                onClick={() => setConfirmOpen(true)}
                aria-label="Delete evaluator"
              >
                <Trash className="size-3.5" />
              </Button>
            </div>
          </div>

          {/* Zone B: Section label + content */}
          <div className="flex flex-col gap-1">
            <span className="comet-body-xs-accented text-[10px] tracking-wider text-muted-slate">
              {getSectionLabel(row.type)}
            </span>
            {isLLMJudge ? (
              <AssertionsList assertions={assertions} />
            ) : (
              configSummary && (
                <div className="rounded bg-foreground/[0.03] px-2 py-1">
                  <span className="text-foreground">{configSummary}</span>
                </div>
              )
            )}
          </div>
        </CardContent>
      </Card>

      <ConfirmDialog
        open={confirmOpen}
        setOpen={setConfirmOpen}
        onConfirm={() => onDelete(row.id)}
        title="Delete evaluator"
        description={`Are you sure you want to delete "${row.name}"?`}
        confirmText="Delete"
      />
    </>
  );
};

interface ItemEvaluatorsSectionProps {
  itemId: string;
  itemEvaluators?: Evaluator[];
}

const ItemEvaluatorsSection: React.FC<ItemEvaluatorsSectionProps> = ({
  itemId,
  itemEvaluators = EMPTY_EVALUATORS,
}) => {
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editingEvaluator, setEditingEvaluator] =
    useState<EvaluatorDisplayRow>();

  const addItemEvaluator = useAddItemEvaluator();
  const editItemEvaluator = useEditItemEvaluator();
  const deleteItemEvaluator = useDeleteItemEvaluator();
  const itemAddedEvaluators = useItemAddedEvaluators(itemId);
  const itemEditedEvaluators = useItemEditedEvaluators(itemId);
  const itemDeletedEvaluatorIds = useItemDeletedEvaluatorIds(itemId);

  const itemDisplayRows = useEvaluatorDisplayRows(
    itemEvaluators,
    itemAddedEvaluators,
    itemEditedEvaluators,
    itemDeletedEvaluatorIds,
  );

  const handleAddSubmit = useCallback(
    (evaluator: Omit<EvaluatorDisplayRow, "id">) => {
      addItemEvaluator(itemId, evaluator);
    },
    [itemId, addItemEvaluator],
  );

  const handleEditSubmit = useCallback(
    (evaluator: Omit<EvaluatorDisplayRow, "id">) => {
      if (editingEvaluator) {
        editItemEvaluator(itemId, editingEvaluator.id, evaluator);
      }
    },
    [itemId, editingEvaluator, editItemEvaluator],
  );

  const openAddDialog = useCallback(() => {
    setEditingEvaluator(undefined);
    setDialogOpen(true);
  }, []);

  const openEditDialog = useCallback((row: EvaluatorDisplayRow) => {
    setEditingEvaluator(row);
    setDialogOpen(true);
  }, []);

  const handleDelete = useCallback(
    (id: string) => {
      deleteItemEvaluator(itemId, id);
    },
    [itemId, deleteItemEvaluator],
  );

  return (
    <div>
      <h3 className="comet-body-s-accented mb-2">Evaluators</h3>

      {itemDisplayRows.length > 0 && (
        <div className="mb-3 flex flex-col gap-2">
          {itemDisplayRows.map((row) => (
            <EvaluatorCard
              key={row.id}
              row={row}
              onEdit={openEditDialog}
              onDelete={handleDelete}
            />
          ))}
        </div>
      )}

      <Button variant="outline" size="sm" onClick={openAddDialog}>
        <Plus className="mr-1 size-4" />
        Add evaluator
      </Button>

      <AddEditEvaluatorDialog
        open={dialogOpen}
        setOpen={setDialogOpen}
        evaluator={editingEvaluator}
        onSubmit={editingEvaluator ? handleEditSubmit : handleAddSubmit}
        existingNames={itemDisplayRows.map((r) => r.name)}
      />
    </div>
  );
};

export default ItemEvaluatorsSection;
