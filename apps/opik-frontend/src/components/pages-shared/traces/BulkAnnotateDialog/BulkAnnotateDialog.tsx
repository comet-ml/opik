import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
  DialogClose,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Input } from "@/components/ui/input";
import { Slider } from "@/components/ui/slider";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Span, Trace, FEEDBACK_SCORE_TYPE } from "@/types/traces";
import {
  FeedbackDefinition,
  FEEDBACK_DEFINITION_TYPE,
} from "@/types/feedback-definitions";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import useTraceFeedbackScoreBatchMutation from "@/api/traces/useTraceFeedbackScoreBatchMutation";
import useAppStore from "@/store/AppStore";

type BulkAnnotateDialogProps = {
  open: boolean;
  setOpen: (open: boolean | number) => void;
  selectedRows: Array<Trace | Span>;
  projectName: string;
};

const BulkAnnotateDialog: React.FC<BulkAnnotateDialogProps> = ({
  open,
  setOpen,
  selectedRows,
  projectName,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [selectedDefinitionId, setSelectedDefinitionId] = useState<string>("");
  const [scoreValue, setScoreValue] = useState<number | string>("");
  const [reason, setReason] = useState<string>("");

  const { data: definitionsData, isLoading: isLoadingDefinitions } =
    useFeedbackDefinitionsList(
      {
        workspaceName,
        page: 1,
        size: 1000,
      },
      {
        placeholderData: keepPreviousData,
        enabled: open,
      },
    );

  const definitions = useMemo(
    () => definitionsData?.content ?? [],
    [definitionsData],
  );

  const selectedDefinition = useMemo(
    () => definitions.find((d) => d.id === selectedDefinitionId),
    [definitions, selectedDefinitionId],
  );

  const { mutate: batchMutate, isPending } =
    useTraceFeedbackScoreBatchMutation();

  const handleDefinitionChange = useCallback((value: string) => {
    setSelectedDefinitionId(value);
    setScoreValue("");
  }, []);

  const handleSubmit = useCallback(() => {
    if (!selectedDefinition || scoreValue === "") return;

    const numericValue =
      typeof scoreValue === "string" ? parseFloat(scoreValue) : scoreValue;

    if (isNaN(numericValue)) return;

    batchMutate(
      {
        scores: selectedRows.map((row) => ({
          id: row.id,
          projectName,
          name: selectedDefinition.name,
          value: numericValue,
          categoryName:
            selectedDefinition.type === FEEDBACK_DEFINITION_TYPE.categorical
              ? getCategoryNameByValue(selectedDefinition, numericValue)
              : undefined,
          reason: reason || undefined,
          source: FEEDBACK_SCORE_TYPE.ui,
        })),
      },
      {
        onSuccess: () => {
          setOpen(false);
          setSelectedDefinitionId("");
          setScoreValue("");
          setReason("");
        },
      },
    );
  }, [
    selectedDefinition,
    scoreValue,
    selectedRows,
    projectName,
    reason,
    batchMutate,
    setOpen,
  ]);

  const renderValueInput = () => {
    if (!selectedDefinition) return null;

    switch (selectedDefinition.type) {
      case FEEDBACK_DEFINITION_TYPE.numerical: {
        const { min, max } = selectedDefinition.details;
        const numValue =
          typeof scoreValue === "number"
            ? scoreValue
            : scoreValue === ""
              ? min
              : parseFloat(scoreValue);
        return (
          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <Label>Value</Label>
              <span className="text-sm text-muted-foreground">
                {numValue.toFixed(2)}
              </span>
            </div>
            <Slider
              value={[numValue]}
              onValueChange={(values) => setScoreValue(values[0])}
              min={min}
              max={max}
              step={(max - min) / 100}
            />
            <div className="flex justify-between text-xs text-muted-foreground">
              <span>{min}</span>
              <span>{max}</span>
            </div>
          </div>
        );
      }

      case FEEDBACK_DEFINITION_TYPE.categorical: {
        const categories = Object.entries(
          selectedDefinition.details.categories,
        );
        return (
          <div className="space-y-2">
            <Label>Category</Label>
            <Select
              value={scoreValue.toString()}
              onValueChange={(v) => setScoreValue(parseFloat(v))}
            >
              <SelectTrigger>
                <SelectValue placeholder="Select a category" />
              </SelectTrigger>
              <SelectContent>
                {categories.map(([name, value]) => (
                  <SelectItem key={name} value={value.toString()}>
                    {name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        );
      }

      case FEEDBACK_DEFINITION_TYPE.boolean: {
        const { true_label, false_label } = selectedDefinition.details;
        return (
          <div className="space-y-2">
            <Label>Value</Label>
            <Select
              value={scoreValue.toString()}
              onValueChange={(v) => setScoreValue(parseFloat(v))}
            >
              <SelectTrigger>
                <SelectValue placeholder="Select a value" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="1">{true_label || "True"}</SelectItem>
                <SelectItem value="0">{false_label || "False"}</SelectItem>
              </SelectContent>
            </Select>
          </div>
        );
      }

      default:
        return null;
    }
  };

  const isSubmitDisabled =
    !selectedDefinition || scoreValue === "" || isPending;

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>
            Annotate {selectedRows.length} trace
            {selectedRows.length !== 1 ? "s" : ""}
          </DialogTitle>
        </DialogHeader>

        <div className="space-y-4 py-4">
          <div className="space-y-2">
            <Label>Feedback Score</Label>
            <Select
              value={selectedDefinitionId}
              onValueChange={handleDefinitionChange}
              disabled={isLoadingDefinitions}
            >
              <SelectTrigger>
                <SelectValue
                  placeholder={
                    isLoadingDefinitions
                      ? "Loading..."
                      : "Select a feedback score"
                  }
                />
              </SelectTrigger>
              <SelectContent>
                {definitions.map((def) => (
                  <SelectItem key={def.id} value={def.id}>
                    {def.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {renderValueInput()}

          <div className="space-y-2">
            <Label>Reason (optional)</Label>
            <Input
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="Add a reason for this annotation"
            />
          </div>
        </div>

        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button onClick={handleSubmit} disabled={isSubmitDisabled}>
            {isPending ? "Applying..." : "Apply to all"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

function getCategoryNameByValue(
  definition: FeedbackDefinition,
  value: number,
): string | undefined {
  if (definition.type !== FEEDBACK_DEFINITION_TYPE.categorical) return undefined;
  const categories = definition.details.categories;
  const entry = Object.entries(categories).find(([, v]) => v === value);
  return entry ? entry[0] : undefined;
}

export default BulkAnnotateDialog;
