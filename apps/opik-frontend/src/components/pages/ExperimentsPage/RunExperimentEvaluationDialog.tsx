import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { Sparkles } from "lucide-react";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Card, CardContent } from "@/components/ui/card";
import useRulesList from "@/api/automations/useRulesList";
import useExperimentEvaluationMutation from "@/api/datasets/useExperimentEvaluationMutation";
import useAppStore from "@/store/AppStore";
import { EVALUATORS_RULE_TYPE, EvaluatorsRule } from "@/types/automations";
import Loader from "@/components/shared/Loader/Loader";

const STALE_TIME = 5 * 60 * 1000;

type RunExperimentEvaluationDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  projectId: string;
  experimentIds: string[];
};

const RunExperimentEvaluationDialog: React.FunctionComponent<
  RunExperimentEvaluationDialogProps
> = ({ open, setOpen, projectId, experimentIds }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [selectedRuleIds, setSelectedRuleIds] = useState<Set<string>>(
    new Set(),
  );

  const { data, isLoading } = useRulesList(
    {
      workspaceName,
      projectId,
      page: 1,
      size: 1000,
    },
    {
      enabled: open,
      placeholderData: keepPreviousData,
      staleTime: STALE_TIME,
    },
  );

  const experimentEvaluationMutation = useExperimentEvaluationMutation();

  const rules = useMemo(() => {
    const allRules = data?.content || [];
    return allRules.filter((rule) =>
      [
        EVALUATORS_RULE_TYPE.llm_judge,
        EVALUATORS_RULE_TYPE.python_code,
      ].includes(rule.type),
    );
  }, [data]);

  const handleCheckboxChange = useCallback(
    (ruleId: string, checked: boolean) => {
      setSelectedRuleIds((prev) => {
        const newSet = new Set(prev);
        if (checked) {
          newSet.add(ruleId);
        } else {
          newSet.delete(ruleId);
        }
        return newSet;
      });
    },
    [],
  );

  const handleConfirm = useCallback(() => {
    experimentEvaluationMutation.mutate(
      {
        projectId,
        experimentIds,
        ruleIds: Array.from(selectedRuleIds),
      },
      {
        onSuccess: () => {
          setOpen(false);
          setSelectedRuleIds(new Set());
        },
      },
    );
  }, [
    selectedRuleIds,
    experimentEvaluationMutation,
    projectId,
    experimentIds,
    setOpen,
  ]);

  const handleOpenChange = useCallback(
    (newOpen: boolean) => {
      setOpen(newOpen);
      if (!newOpen) {
        setSelectedRuleIds(new Set());
      }
    },
    [setOpen],
  );

  const renderEmptyState = () => {
    return (
      <div className="p-4">
        <div className="flex flex-col items-center justify-center gap-2 py-8">
          <Sparkles className="size-4 text-muted-foreground" />
          <p className="comet-body-s-accented text-center">
            No trace-level evaluation rules assigned to this project
          </p>
          <p className="comet-body-s text-center text-muted-foreground">
            Create trace-level evaluation rules in the Online evaluation page to
            evaluate experiments
          </p>
        </div>
      </div>
    );
  };

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>Evaluate experiments</DialogTitle>
        </DialogHeader>
        {isLoading ? (
          <Loader />
        ) : rules.length === 0 ? (
          renderEmptyState()
        ) : (
          <div className="space-y-4">
            <p className="comet-body-s text-muted-foreground">
              Select the evaluation rules to apply to traces in the selected
              experiments. Only trace-level rules are supported for experiment
              evaluation.
            </p>
            <div className="max-h-96 space-y-2 overflow-y-auto">
              {rules.map((rule: EvaluatorsRule) => (
                <Card key={rule.id} className="border-muted">
                  <CardContent className="p-4">
                    <div className="flex items-start gap-3">
                      <Checkbox
                        id={rule.id}
                        checked={selectedRuleIds.has(rule.id)}
                        onCheckedChange={(checked) =>
                          handleCheckboxChange(rule.id, checked as boolean)
                        }
                      />
                      <div className="flex-1">
                        <label
                          htmlFor={rule.id}
                          className="comet-body-s-accented cursor-pointer"
                        >
                          {rule.name}
                        </label>
                      </div>
                    </div>
                  </CardContent>
                </Card>
              ))}
            </div>
          </div>
        )}
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button
            onClick={handleConfirm}
            disabled={selectedRuleIds.size === 0 || isLoading}
          >
            Evaluate
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default RunExperimentEvaluationDialog;
