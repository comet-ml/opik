import { useCallback, useState } from "react";
import uniq from "lodash/uniq";

import useAppStore from "@/store/AppStore";
import { useToast } from "@/ui/use-toast";
import useFilteredRulesList from "@/api/automations/useFilteredRulesList";
import { getCompareExperimentsList } from "@/api/datasets/useCompareExperimentsList";
import { COMPARE_EXPERIMENTS_MAX_PAGE_SIZE } from "@/constants/experiments";
import {
  Experiment,
  ExperimentItem,
  ExperimentsCompare,
} from "@/types/datasets";

type UseEvaluateExperimentTracesParams = {
  experiment: Experiment;
};

const useEvaluateExperimentTraces = ({
  experiment,
}: UseEvaluateExperimentTracesParams) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { toast } = useToast();

  const projectId = experiment.project_id ?? "";

  const [open, setOpen] = useState(false);
  const [traceIds, setTraceIds] = useState<string[]>([]);
  const [isFetching, setIsFetching] = useState(false);

  const { rules, isLoading: isRulesLoading } = useFilteredRulesList({
    projectId,
    entityType: "trace",
    enabled: Boolean(projectId),
  });

  const handleClick = useCallback(async () => {
    if (!experiment.dataset_id) return;
    setIsFetching(true);
    try {
      const data: { content?: ExperimentsCompare[] } =
        await getCompareExperimentsList(
          {},
          {
            workspaceName,
            datasetId: experiment.dataset_id,
            experimentsIds: [experiment.id],
            truncate: true,
            page: 1,
            size: COMPARE_EXPERIMENTS_MAX_PAGE_SIZE,
          },
        );
      const ids = uniq(
        (data?.content ?? [])
          .flatMap((row) => row.experiment_items ?? [])
          .map((item: ExperimentItem) => item.trace_id)
          .filter((id): id is string => Boolean(id)),
      );

      if (!ids.length) {
        toast({
          title: "Nothing to evaluate",
          description: "No traces are associated with this experiment.",
          variant: "destructive",
        });
        return;
      }

      setTraceIds(ids);
      setOpen(true);
    } catch {
      toast({
        title: "Error",
        description: "Failed to load experiment traces for evaluation.",
        variant: "destructive",
      });
    } finally {
      setIsFetching(false);
    }
  }, [experiment.dataset_id, experiment.id, workspaceName, toast]);

  return {
    projectId,
    open,
    setOpen,
    traceIds,
    isFetching,
    rules,
    isRulesLoading,
    handleClick,
  };
};

export default useEvaluateExperimentTraces;
