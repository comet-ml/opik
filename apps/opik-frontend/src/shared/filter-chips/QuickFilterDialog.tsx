import React, { useEffect, useState } from "react";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Button } from "@/ui/button";
import { Input } from "@/ui/input";
import { FilterOperator } from "@/types/filters";
import { OperatorCell } from "@/shared/filter-chips/chips/QueryBuilderChip/cells/OperatorCell";
import {
  getOperatorLabel,
  operatorNeedsValue,
} from "@/shared/filter-chips/chips/QueryBuilderChip/operators";

export type QuickFilterDraft = {
  // Changes on each open so the dialog resets its local edit state.
  id: string;
  chipId: string;
  key?: string;
  chipLabel: string;
  field: string;
  operators: FilterOperator[];
  defaultOperator: FilterOperator;
  value: string;
};

type QuickFilterDialogProps = {
  draft: QuickFilterDraft | null;
  onApply: (operator: FilterOperator, value: string) => void;
  onClose: () => void;
};

const QuickFilterDialog: React.FC<QuickFilterDialogProps> = ({
  draft,
  onApply,
  onClose,
}) => {
  const [operator, setOperator] = useState<FilterOperator>("=");
  const [value, setValue] = useState("");

  useEffect(() => {
    if (draft) {
      setOperator(draft.defaultOperator);
      setValue(draft.value);
    }
    // Reset only when a new draft is opened.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draft?.id]);

  const operatorOptions = (draft?.operators ?? []).map((op) => ({
    label: getOperatorLabel(op),
    value: op,
  }));

  const needsValue = operatorNeedsValue(operator);
  const canApply = !needsValue || value.trim() !== "";

  const handleApply = () => {
    if (!canApply) return;
    onApply(operator, value);
  };

  return (
    <Dialog
      open={Boolean(draft)}
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <DialogContent className="sm:max-w-[460px]">
        <DialogHeader>
          <DialogTitle>Add filter</DialogTitle>
        </DialogHeader>
        {draft && (
          <div className="flex flex-col gap-2 py-2">
            <div className="comet-body-s text-muted-slate">
              Filter rows where{" "}
              <span className="comet-body-s-accented break-all text-foreground">
                {draft.field === draft.chipLabel
                  ? draft.field
                  : `${draft.chipLabel} · ${draft.field}`}
              </span>
            </div>
            <div className="flex items-center gap-2">
              <OperatorCell
                value={operator}
                options={operatorOptions}
                onSelect={(next) => setOperator(next)}
                ariaLabel="Operator"
              />
              {needsValue && (
                <Input
                  autoFocus
                  value={value}
                  onChange={(event) => setValue(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter") handleApply();
                  }}
                  placeholder="value"
                  className="h-8 flex-1"
                />
              )}
            </div>
          </div>
        )}
        <DialogFooter>
          <Button variant="outline" size="sm" onClick={onClose}>
            Cancel
          </Button>
          <Button size="sm" onClick={handleApply} disabled={!canApply}>
            Apply filter
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default QuickFilterDialog;
