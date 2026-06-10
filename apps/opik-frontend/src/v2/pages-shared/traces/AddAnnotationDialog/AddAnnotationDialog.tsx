import React, { useCallback, useState } from "react";
import { Span, Trace } from "@/types/traces";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import {
  BATCH_ANNOTATION_ENTITY_TYPE,
  FeedbackScoreBatchItem,
} from "@/api/traces/useFeedbackScoreBatchSetMutation";
import useFeedbackScoreBatchSetMutation from "@/api/traces/useFeedbackScoreBatchSetMutation";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import useAppStore from "@/store/AppStore";
import { Button } from "@/ui/button";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Label } from "@/ui/label";
import { Input } from "@/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/ui/select";
import { useToast } from "@/ui/use-toast";
import {
  FeedbackDefinition,
  FEEDBACK_DEFINITION_TYPE,
} from "@/types/feedback-definitions";
import { Loader2 } from "lucide-react";

type AddAnnotationDialogProps = {
  rows: Array<Trace | Span>;
  open: boolean;
  setOpen: (open: boolean) => void;
  projectId: string;
  type: TRACE_DATA_TYPE;
  onSuccess?: () => void;
};

type AnnotationFormState = {
  definitionId: string;
  value: string;
  reason: string;
};

const INITIAL_FORM: AnnotationFormState = {
  definitionId: "",
  value: "",
  reason: "",
};

/**
 * Dialog for bulk-annotating a selection of traces or spans with a feedback
 * score. Uses `useFeedbackScoreBatchSetMutation` which posts to the backend
 * batch endpoints (`PUT /traces/feedback-scores` or `PUT /spans/feedback-scores`).
 *
 * Matches the UX pattern of `AddTagDialog`: a lightweight modal that operates
 * on the already-selected rows and closes on success.
 */
const AddAnnotationDialog: React.FC<AddAnnotationDialogProps> = ({
  rows,
  open,
  setOpen,
  projectId,
  type,
  onSuccess,
}) => {
  const { toast } = useToast();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [form, setForm] = useState<AnnotationFormState>(INITIAL_FORM);

  const { data: definitionsData, isLoading: loadingDefs } =
    useFeedbackDefinitionsList({
      workspaceName,
      page: 1,
      size: 100,
    });

  const batchSetMutation = useFeedbackScoreBatchSetMutation();

  const definitions = definitionsData?.content ?? [];
  const selectedDef: FeedbackDefinition | undefined = definitions.find(
    (d) => d.id === form.definitionId,
  );

  const entityType =
    type === TRACE_DATA_TYPE.spans
      ? BATCH_ANNOTATION_ENTITY_TYPE.spans
      : BATCH_ANNOTATION_ENTITY_TYPE.traces;

  const entityLabel = rows.length === 1 ? "item" : `${rows.length} items`;

  const isValueValid = useCallback((): boolean => {
    if (!selectedDef || form.value === "") return false;

    const numericValue = parseFloat(form.value);
    if (isNaN(numericValue)) return false;

    if (selectedDef.type === FEEDBACK_DEFINITION_TYPE.numerical) {
      const { min, max } = selectedDef.details;
      return numericValue >= min && numericValue <= max;
    }

    if (selectedDef.type === FEEDBACK_DEFINITION_TYPE.boolean) {
      return numericValue === 0 || numericValue === 1;
    }

    if (selectedDef.type === FEEDBACK_DEFINITION_TYPE.categorical) {
      const validValues = Object.values(selectedDef.details.categories);
      return validValues.includes(numericValue);
    }

    return false;
  }, [selectedDef, form.value]);

  const handleSubmit = useCallback(async () => {
    if (!selectedDef || !isValueValid()) return;

    const numericValue = parseFloat(form.value);
    const selectedCategoryName =
      selectedDef.type === FEEDBACK_DEFINITION_TYPE.categorical
        ? Object.entries(selectedDef.details.categories).find(
            ([, value]) => value === numericValue,
          )?.[0]
        : undefined;

    const scores: FeedbackScoreBatchItem[] = rows.map((row) => ({
      id: row.id,
      name: selectedDef.name,
      value: numericValue,
      ...(selectedCategoryName && { categoryName: selectedCategoryName }),
      ...(form.reason.trim() && { reason: form.reason.trim() }),
    }));

    await batchSetMutation.mutateAsync({ projectId, entityType, scores });

    toast({
      title: "Annotations saved",
      description: `Successfully annotated ${entityLabel} with "${selectedDef.name}".`,
    });

    setForm(INITIAL_FORM);
    setOpen(false);
    onSuccess?.();
  }, [
    selectedDef,
    isValueValid,
    form.value,
    form.reason,
    rows,
    batchSetMutation,
    projectId,
    entityType,
    entityLabel,
    toast,
    setOpen,
    onSuccess,
  ]);

  const handleOpenChange = (nextOpen: boolean) => {
    if (!nextOpen) {
      setForm(INITIAL_FORM);
    }
    setOpen(nextOpen);
  };

  const renderValueInput = () => {
    if (!selectedDef) return null;

    if (selectedDef.type === FEEDBACK_DEFINITION_TYPE.categorical) {
      const categories = selectedDef.details.categories;
      return (
        <Select
          value={form.value}
          onValueChange={(v) => setForm((prev) => ({ ...prev, value: v }))}
        >
          <SelectTrigger>
            <SelectValue placeholder="Select a category" />
          </SelectTrigger>
          <SelectContent>
            {Object.entries(categories).map(([label, val]) => (
              <SelectItem key={label} value={String(val)}>
                {label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      );
    }

    if (selectedDef.type === FEEDBACK_DEFINITION_TYPE.boolean) {
      return (
        <Select
          value={form.value}
          onValueChange={(v) => setForm((prev) => ({ ...prev, value: v }))}
        >
          <SelectTrigger>
            <SelectValue placeholder="Select true / false" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="1">
              {selectedDef.details.true_label || "True"}
            </SelectItem>
            <SelectItem value="0">
              {selectedDef.details.false_label || "False"}
            </SelectItem>
          </SelectContent>
        </Select>
      );
    }

    // Numerical
    const { min, max } = selectedDef.details;
    return (
      <Input
        type="number"
        min={min}
        max={max}
        step="any"
        placeholder={`${min} – ${max}`}
        value={form.value}
        onChange={(e) =>
          setForm((prev) => ({ ...prev, value: e.target.value }))
        }
      />
    );
  };

  const isPending = batchSetMutation.isPending;
  const canSubmit =
    !isPending && !!selectedDef && isValueValid() && rows.length > 0;

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>
            Annotate {entityLabel}
          </DialogTitle>
        </DialogHeader>

        <div className="space-y-4 py-2">
          {/* Feedback score selector */}
          <div className="space-y-1.5">
            <Label>Score name</Label>
            {loadingDefs ? (
              <div className="flex items-center gap-2 text-muted-foreground text-sm">
                <Loader2 className="size-4 animate-spin" />
                Loading definitions…
              </div>
            ) : (
              <Select
                value={form.definitionId}
                onValueChange={(id) =>
                  setForm({ definitionId: id, value: "", reason: "" })
                }
              >
                <SelectTrigger>
                  <SelectValue placeholder="Choose a score…" />
                </SelectTrigger>
                <SelectContent>
                  {definitions.map((def) => (
                    <SelectItem key={def.id} value={def.id}>
                      {def.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            )}
          </div>

          {/* Value input – rendered based on the selected definition's type */}
          {selectedDef && (
            <div className="space-y-1.5">
              <Label>Value</Label>
              {renderValueInput()}
            </div>
          )}

          {/* Optional reason */}
          {selectedDef && (
            <div className="space-y-1.5">
              <Label>
                Reason{" "}
                <span className="text-muted-foreground font-normal">
                  (optional)
                </span>
              </Label>
              <Input
                placeholder="Add a reason…"
                value={form.reason}
                onChange={(e) =>
                  setForm((prev) => ({ ...prev, reason: e.target.value }))
                }
              />
            </div>
          )}
        </div>

        <DialogFooter>
          <Button
            variant="outline"
            onClick={() => handleOpenChange(false)}
            disabled={isPending}
          >
            Cancel
          </Button>
          <Button onClick={handleSubmit} disabled={!canSubmit}>
            {isPending && <Loader2 className="mr-2 size-4 animate-spin" />}
            Annotate
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddAnnotationDialog;
