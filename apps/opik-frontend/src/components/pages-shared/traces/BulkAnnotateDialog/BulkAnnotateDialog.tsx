import React, { useCallback, useEffect, useMemo, useState } from "react";
import { MessageSquarePlus } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import useAppStore from "@/store/AppStore";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import useTraceBatchFeedbackScoreSetMutation from "@/api/traces/useTraceBatchFeedbackScoreSetMutation";
import {
  FEEDBACK_DEFINITION_TYPE,
  FeedbackDefinition,
} from "@/types/feedback-definitions";
import { FEEDBACK_SCORE_TYPE, Trace, Span } from "@/types/traces";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Label } from "@/components/ui/label";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { useToast } from "@/components/ui/use-toast";
import sortBy from "lodash/sortBy";
import isNumber from "lodash/isNumber";
import { isNumericFeedbackScoreValid } from "@/lib/traces";

type BulkAnnotateDialogProps = {
  open: boolean;
  setOpen: (open: boolean | number) => void;
  selectedRows: Array<Trace | Span>;
  projectId: string;
};

const BulkAnnotateDialog: React.FC<BulkAnnotateDialogProps> = ({
  open,
  setOpen,
  selectedRows,
  projectId,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { toast } = useToast();

  const { data: feedbackDefinitionsData } = useFeedbackDefinitionsList({
    workspaceName,
    page: 1,
    size: 1000,
  });

  const feedbackDefinitions = useMemo(
    () => sortBy(feedbackDefinitionsData?.content || [], "name"),
    [feedbackDefinitionsData],
  );

  const [selectedDefinitionId, setSelectedDefinitionId] = useState<string>(
    ""
  );
  const [value, setValue] = useState<number | string>("");
  const [reason, setReason] = useState<string>("");

  const selectedDefinition: FeedbackDefinition | undefined = useMemo(() => {
    return feedbackDefinitions.find((d) => d.id === selectedDefinitionId);
  }, [feedbackDefinitions, selectedDefinitionId]);

  const [booleanValue, setBooleanValue] = useState<string>("");

  const handleBooleanChange = useCallback((val: string) => {
    setBooleanValue(val);
    if (selectedDefinition?.type === FEEDBACK_DEFINITION_TYPE.boolean) {
      const details = selectedDefinition.details as {
        true_label: string;
        false_label: string;
      };
      if (val === details.true_label) {
        setValue(1);
      } else {
        setValue(0);
      }
    }
  }, [selectedDefinition]);

  const { mutate: batchSetScore, isPending: isSubmitting } =
    useTraceBatchFeedbackScoreSetMutation();

  const handleSubmit = useCallback(() => {
    if (!selectedDefinition) return;

    const numericValue = Number(value);
    if (
      selectedDefinition.type !== FEEDBACK_DEFINITION_TYPE.boolean &&
      !isNumericFeedbackScoreValid(numericValue, selectedDefinition)
    ) {
      return;
    }

    const scores = selectedRows.map((row) => ({
      id: row.id,
      name: selectedDefinition.name,
      value:
        selectedDefinition.type === FEEDBACK_DEFINITION_TYPE.boolean
          ? Number(value)
          : numericValue,
      reason: reason || undefined,
      source: FEEDBACK_SCORE_TYPE.ui,
    }));

    batchSetScore(
      { scores },
      {
        onSuccess: () => {
          toast({
            title: "Success",
            description: `Annotated ${selectedRows.length} traces with "${selectedDefinition.name}"`,
          });
          setOpen(false);
        },
      }
    );
  }, [
    selectedDefinition,
    value,
    reason,
    selectedRows,
    batchSetScore,
    toast,
    setOpen,
  ]);

  const handleClose = useCallback(() => {
    setOpen(false);
  }, [setOpen]);

  // Reset form when dialog opens
  useEffect(() => {
    if (open) {
      setSelectedDefinitionId("");
      setValue("");
      setReason("");
      setBooleanValue("");
    }
  }, [open]);

  const isSubmitDisabled =
    !selectedDefinitionId ||
    (selectedDefinition?.type !== FEEDBACK_DEFINITION_TYPE.boolean &&
      (!isNumber(Number(value)) ||
        !isNumericFeedbackScoreValid(selectedDefinition.details, Number(value))));

  return (
    <Dialog open={open} onOpenChange={(v) => setOpen(v)}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Bulk Annotate Traces</DialogTitle>
          <DialogDescription>
            Apply a feedback score to {selectedRows.length} selected trace
            {selectedRows.length > 1 ? "s" : ""} at once.
          </DialogDescription>
        </DialogHeader>

        <div className="grid gap-4 py-4">
          {/* Feedback Definition Select */}
          <div className="grid gap-2">
            <Label>Feedback Score</Label>
            <Select
              value={selectedDefinitionId}
              onValueChange={(val) => {
                setSelectedDefinitionId(val);
                setValue("");
                setBooleanValue("");
              }}
            >
              <SelectTrigger>
                <SelectValue placeholder="Select a feedback score..." />
              </SelectTrigger>
              <SelectContent>
                {feedbackDefinitions.map((def) => (
                  <SelectItem key={def.id} value={def.id}>
                    {def.name}{" "}
                    <span className="text-light-slate">
                      ({def.type})
                    </span>
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {/* Value Input */}
          {selectedDefinition && (
            <div className="grid gap-2">
              <Label>Value</Label>
              {selectedDefinition.type ===
              FEEDBACK_DEFINITION_TYPE.boolean ? (
                <ToggleGroup
                  type="single"
                  value={booleanValue}
                  onValueChange={handleBooleanChange}
                  className="justify-start"
                >
                  {(
                    selectedDefinition.details as {
                      true_label: string;
                      false_label: string;
                    }
                  ).false_label && (
                    <ToggleGroupItem
                      value={
                        (
                          selectedDefinition.details as {
                            true_label: string;
                            false_label: string;
                          }
                        ).false_label
                      }
                    >
                      {
                        (
                          selectedDefinition.details as {
                            true_label: string;
                            false_label: string;
                          }
                        ).false_label
                      }
                    </ToggleGroupItem>
                  )}
                  {(
                    selectedDefinition.details as {
                      true_label: string;
                      false_label: string;
                    }
                  ).true_label && (
                    <ToggleGroupItem
                      value={
                        (
                          selectedDefinition.details as {
                            true_label: string;
                            false_label: string;
                          }
                        ).true_label
                      }
                    >
                      {
                        (
                          selectedDefinition.details as {
                            true_label: string;
                            false_label: string;
                          }
                        ).true_label
                      }
                    </ToggleGroupItem>
                  )}
                </ToggleGroup>
              ) : selectedDefinition.type ===
                FEEDBACK_DEFINITION_TYPE.categorical ? (
                <Select
                  value={isNumber(Number(value)) ? String(value) : ""}
                  onValueChange={(val) => setValue(val)}
                >
                  <SelectTrigger>
                    <SelectValue placeholder="Select category..." />
                  </SelectTrigger>
                  <SelectContent>
                    {Object.entries(
                      (selectedDefinition.details as { categories: Record<string, number> }).categories
                    ).map(([label]) => (
                      <SelectItem key={label} value={label}>
                        {label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              ) : (
                <div className="flex items-center gap-2">
                  <Input
                    type="number"
                    value={value}
                    onChange={(e) => setValue(e.target.value)}
                    placeholder="Enter value..."
                    min={
                      (selectedDefinition.details as { min: number }).min
                    }
                    max={
                      (selectedDefinition.details as { max: number }).max
                    }
                    step="any"
                  />
                  {selectedDefinition.type ===
                    FEEDBACK_DEFINITION_TYPE.numerical && (
                    <span className="text-sm text-light-slate">
                      [{(selectedDefinition.details as { min: number }).min} -{" "}
                      {(selectedDefinition.details as { max: number }).max}]
                    </span>
                  )}
                </div>
              )}
            </div>
          )}

          {/* Reason Input */}
          <div className="grid gap-2">
            <Label>Reason (optional)</Label>
            <Textarea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="Add a reason for this annotation..."
              rows={2}
            />
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={handleClose}>
            Cancel
          </Button>
          <Button
            onClick={handleSubmit}
            disabled={isSubmitDisabled || isSubmitting}
          >
            {isSubmitting ? "Annotating..." : "Annotate"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default BulkAnnotateDialog;
