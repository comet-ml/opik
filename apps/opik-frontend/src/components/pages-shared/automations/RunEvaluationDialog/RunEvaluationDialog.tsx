import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { Sparkles, ChevronDown, Plus } from "lucide-react";
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
import ColoredTagNew from "@/components/shared/ColoredTag/ColoredTagNew";
import { cn } from "@/lib/utils";
import useRulesList from "@/api/automations/useRulesList";
import useManualEvaluationMutation from "@/api/automations/useManualEvaluationMutation";
import useAppStore from "@/store/AppStore";
import {
  EVALUATORS_RULE_SCOPE,
  EVALUATORS_RULE_TYPE,
  EvaluatorsRule,
} from "@/types/automations";
import Loader from "@/components/shared/Loader/Loader";
import AddEditRuleDialog from "@/components/pages-shared/automations/AddEditRuleDialog/AddEditRuleDialog";

type ManualEvaluationEntityType = "trace" | "thread" | "span";

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
  const [expandedRuleIds, setExpandedRuleIds] = useState<Set<string>>(
    new Set(),
  );
  const [openCreateRuleDialog, setOpenCreateRuleDialog] =
    useState<boolean>(false);
  const hasPreSelectedRef = useRef(false);

  const { data, isLoading } = useRulesList(
    {
      workspaceName,
      projectId,
      page: 1,
      size: 1000, // Load all rules in the workspace
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
  // Span rules: span_llm_as_judge, span_user_defined_metric_python
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
    } else if (entityType === "span") {
      return allRules.filter(
        (rule) =>
          rule.type === EVALUATORS_RULE_TYPE.span_llm_judge ||
          rule.type === EVALUATORS_RULE_TYPE.span_python_code,
      );
    } else {
      throw new Error(`Unknown entity type: ${entityType}`);
    }
  }, [data?.content, entityType]);

  // Pre-select all rules when dialog opens and rules are loaded (only once per dialog opening)
  useEffect(() => {
    if (open && rules.length > 0 && !hasPreSelectedRef.current) {
      setSelectedRuleIds(new Set(rules.map((rule) => rule.id)));
      hasPreSelectedRef.current = true;
    }

    // Reset the flag when dialog closes
    if (!open) {
      hasPreSelectedRef.current = false;
    }
  }, [open, rules]);

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

  const toggleExpanded = useCallback((ruleId: string) => {
    setExpandedRuleIds((prev) => {
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
          setExpandedRuleIds(new Set());
        },
      },
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    selectedRuleIds,
    manualEvaluationMutation.mutate,
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
        setExpandedRuleIds(new Set());
      }
    },
    [setOpen],
  );

  const handleCreateRule = useCallback(() => {
    setOpenCreateRuleDialog(true);
  }, []);

  const renderEmptyState = () => {
    return (
      <div className="p-4">
        <div className="flex flex-col items-center justify-center gap-2 py-8">
          <Sparkles className="size-4 text-muted-foreground" />
          <p className="comet-body-s-accented text-center">
            No online evaluation rules assigned to this project
          </p>
          <p className="comet-body-s text-center text-muted-foreground">
            Create a new rule, or assign an existing rule in the Online
            evaluation page
          </p>
          <Button
            variant="link"
            size="sm"
            onClick={handleCreateRule}
            className="mt-2"
          >
            Create a new rule
          </Button>
        </div>
      </div>
    );
  };

  const renderRulesList = () => {
    if (isLoading) {
      return (
        <div className="flex min-h-36 items-center justify-center">
          <Loader />
        </div>
      );
    }

    if (rules.length === 0) {
      return renderEmptyState();
    }

    return (
      <div className="flex flex-col gap-2">
        {rules.map((rule: EvaluatorsRule) => {
          const checked = selectedRuleIds.has(rule.id);
          const isExpanded = expandedRuleIds.has(rule.id);
          const hasCode =
            rule.type === EVALUATORS_RULE_TYPE.llm_judge ||
            rule.type === EVALUATORS_RULE_TYPE.thread_llm_judge ||
            rule.type === EVALUATORS_RULE_TYPE.span_llm_judge;

          // Extract schema names for score tags
          const schemaNames =
            hasCode && rule.code && "schema" in rule.code
              ? rule.code.schema.map((s) => s.name)
              : [];

          return (
            <Card key={rule.id} className="overflow-hidden">
              <CardContent className="p-0">
                <div className="flex items-start gap-3 p-3">
                  <Checkbox
                    checked={checked}
                    onCheckedChange={() => handleCheckboxChange(rule.id)}
                    aria-label={`Select rule ${rule.name}`}
                    className="mt-0.5"
                  />
                  <div className="flex flex-1 flex-col gap-2">
                    <div className="flex items-start justify-between gap-2">
                      <span className="comet-body-s-accented">{rule.name}</span>
                      {schemaNames.length > 0 && (
                        <div className="flex shrink-0 flex-wrap gap-1">
                          {schemaNames.map((schemaName) => (
                            <ColoredTagNew
                              key={schemaName}
                              label={schemaName}
                              className="h-6"
                            />
                          ))}
                        </div>
                      )}
                    </div>
                    {hasCode && rule.code && (
                      <div>
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => toggleExpanded(rule.id)}
                          className="h-auto p-0 text-xs text-muted-foreground hover:bg-transparent hover:text-foreground"
                        >
                          <ChevronDown
                            className={cn(
                              "mr-1 size-3 transition-transform",
                              isExpanded && "rotate-180",
                            )}
                          />
                          {isExpanded ? "Hide" : "Show"} prompt
                        </Button>
                        {isExpanded && (
                          <div className="mt-2 rounded-md bg-muted p-3">
                            <pre className="comet-code max-h-40 overflow-auto whitespace-pre-wrap text-xs">
                              {JSON.stringify(rule.code, null, 2)}
                            </pre>
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                </div>
              </CardContent>
            </Card>
          );
        })}
      </div>
    );
  };

  const entityLabel =
    entityType === "trace"
      ? "traces"
      : entityType === "thread"
        ? "threads"
        : "spans";
  const capitalizedEntityLabel =
    entityType === "trace"
      ? "Traces"
      : entityType === "thread"
        ? "Threads"
        : "Spans";
  const isRunDisabled =
    selectedRuleIds.size === 0 || manualEvaluationMutation.isPending;

  // Map entityType to EVALUATORS_RULE_SCOPE
  const ruleScope =
    entityType === "trace"
      ? EVALUATORS_RULE_SCOPE.trace
      : entityType === "thread"
        ? EVALUATORS_RULE_SCOPE.thread
        : EVALUATORS_RULE_SCOPE.span;

  return (
    <>
      <Dialog open={open} onOpenChange={handleOpenChange}>
        <DialogContent className="max-w-lg sm:max-w-screen-sm">
          <DialogHeader>
            <DialogTitle>Run online evaluation rules</DialogTitle>
          </DialogHeader>
          <div className="w-full overflow-hidden">
            <p className="comet-body-s mb-4 text-muted-foreground">
              Choose the online evaluation rules you want to apply to the
              selected {entityLabel}. Each rule will generate new scores based
              on its configuration.
            </p>
            {rules.length > 0 && (
              <div className="mb-4 flex justify-end">
                <Button variant="ghost" size="sm" onClick={handleCreateRule}>
                  <Plus className="mr-1 size-4" />
                  Create a new rule
                </Button>
              </div>
            )}
            <div className="my-4 flex max-h-[500px] min-h-36 max-w-full flex-col justify-stretch overflow-y-auto">
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
                ? "Evaluating..."
                : `Evaluate ${capitalizedEntityLabel.toLowerCase()}`}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <AddEditRuleDialog
        open={openCreateRuleDialog}
        setOpen={setOpenCreateRuleDialog}
        projectId={projectId}
        defaultScope={ruleScope}
      />
    </>
  );
};

export default RunEvaluationDialog;
