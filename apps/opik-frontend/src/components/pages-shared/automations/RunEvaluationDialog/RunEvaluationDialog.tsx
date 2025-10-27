import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
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
import useRulesList from "@/api/automations/useRulesList";
import useManualEvaluationMutation from "@/api/automations/useManualEvaluationMutation";
import useAppStore from "@/store/AppStore";
import {
  EVALUATORS_RULE_TYPE,
  EvaluatorsRule,
} from "@/types/automations";
import Loader from "@/components/shared/Loader/Loader";

type ManualEvaluationEntityType = "trace" | "thread";

const STALE_TIME = 5 * 60 * 1000; // 5 minutes - rules don't change frequently

type RunEvaluationDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  projectId: string;
  entityIds: string[];
  entityType: ManualEvaluationEntityType;
};

const RunEvaluationDialog: React.FunctionComponent<
  RunEvaluationDialogProps
> = ({ open, setOpen, projectId, entityIds, entityType }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [selectedRuleIds, setSelectedRuleIds] = useState<Set<string>>(
    new Set(),
  );

  const { data, isLoading } = useRulesList(
    {
      workspaceName,
      projectId,
      page: 1,
      size: 1000, // Load all rules for the project
    },
    {
      enabled: open,
      placeholderData: keepPreviousData,
      staleTime: STALE_TIME,
    },
  );

  const manualEvaluationMutation = useManualEvaluationMutation();

  // Filter rules based on entity type
  // Trace rules: llm_as_judge, user_defined_metric_python
  // Thread rules: trace_thread_llm_as_judge, trace_thread_user_defined_metric_python
  const rules = useMemo(() => {
    const allRules = data?.content || [];
    
    if (entityType === "trace") {
      return allRules.filter(
        (rule) =>
          rule.type === EVALUATORS_RULE_TYPE.llm_judge ||
          rule.type === EVALUATORS_RULE_TYPE.python_code,
      );
    } else if (entityType === "thread") {
      return allRules.filter(
        (rule) =>
          rule.type === EVALUATORS_RULE_TYPE.thread_llm_judge ||
          rule.type === EVALUATORS_RULE_TYPE.thread_python_code,
      );
    } else {
      throw new Error(`Unknown entity type: ${entityType}`);
    }
  }, [data?.content, entityType]);

  const handleCheckboxChange = useCallback((ruleId: string) => {
    setSelectedRuleIds((prev) => {
      const newSet = new Set(prev);
      if (newSet.has(ruleId)) {
        newSet.delete(ruleId);
      } else {
        newSet.add(ruleId);
      }
      return newSet;
    });
  }, []);

  const handleRunEvaluation = useCallback(() => {
    if (selectedRuleIds.size === 0) return;

    manualEvaluationMutation.mutate(
      {
        projectId,
        entityIds,
        ruleIds: Array.from(selectedRuleIds),
        entityType,
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
    manualEvaluationMutation,
    projectId,
    entityIds,
    entityType,
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

  const renderRulesList = () => {
    if (isLoading) {
      return (
        <div className="flex min-h-36 items-center justify-center">
          <Loader />
        </div>
      );
    }

    if (rules.length === 0) {
      return (
        <div className="flex min-h-36 items-center justify-center">
          <p className="comet-body-s text-muted-foreground">
            No rules available for this project
          </p>
        </div>
      );
    }

    return rules.map((rule: EvaluatorsRule) => {
      const checked = selectedRuleIds.has(rule.id);
      return (
        <label
          key={rule.id}
          className="flex cursor-pointer items-center gap-2 py-2.5 pl-3 pr-4"
        >
          <Checkbox
            checked={checked}
            onCheckedChange={() => handleCheckboxChange(rule.id)}
            aria-label={`Select rule ${rule.name}`}
          />
          <span className="comet-body-s truncate">{rule.name}</span>
        </label>
      );
    });
  };

  const entityLabel = entityType === "trace" ? "traces" : "threads";
  const isRunDisabled =
    selectedRuleIds.size === 0 || manualEvaluationMutation.isPending;

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>Run evaluation</DialogTitle>
        </DialogHeader>
        <div className="w-full overflow-hidden">
          <p className="comet-body-s mb-4 text-muted-foreground">
            Select which rules to run on {entityIds.length} selected{" "}
            {entityLabel}
          </p>
          <div className="my-4 flex max-h-[400px] min-h-36 max-w-full flex-col justify-stretch overflow-y-auto">
            {renderRulesList()}
          </div>
        </div>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button
            type="submit"
            disabled={isRunDisabled}
            onClick={handleRunEvaluation}
          >
            {manualEvaluationMutation.isPending
              ? "Running..."
              : `Run evaluation with ${selectedRuleIds.size} ${selectedRuleIds.size === 1 ? "rule" : "rules"}`}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default RunEvaluationDialog;

