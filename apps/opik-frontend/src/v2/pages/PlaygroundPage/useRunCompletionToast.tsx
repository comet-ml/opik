import React, { useCallback } from "react";
import { QueryClient, useQueryClient } from "@tanstack/react-query";
import { ExternalLink } from "lucide-react";

import { ToastAction } from "@/ui/toast";
import { useToast } from "@/ui/use-toast";
import { LogExperiment } from "@/types/playground";
import { Experiment } from "@/types/datasets";
import { getExperimentById } from "@/api/datasets/useExperimentById";
import { generateCompareExperimentsURL } from "@/lib/experiments";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import { toPlainDatasetId } from "@/utils/datasetVersionStorage";

const NAME_LOOKUP_TIMEOUT_MS = 3000;

const withServerName = async (
  queryClient: QueryClient,
  experiment: LogExperiment,
): Promise<LogExperiment> => {
  if (experiment.name) return experiment;

  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    // The name box reads the same query, so both show one response. The toast
    // stops waiting on a slow lookup, but the lookup goes on for the box.
    const { name } = await Promise.race([
      queryClient.ensureQueryData<Experiment>({
        queryKey: ["experiment", { experimentId: experiment.id }],
        queryFn: (context) =>
          getExperimentById(context, { experimentId: experiment.id }),
      }),
      new Promise<never>((_, reject) => {
        timer = setTimeout(reject, NAME_LOOKUP_TIMEOUT_MS);
      }),
    ]);
    return { ...experiment, name };
  } catch {
    return experiment;
  } finally {
    clearTimeout(timer);
  }
};

const useRunCompletionToast = (datasetId?: string | null) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const { toast } = useToast();
  const queryClient = useQueryClient();

  const announce = useCallback(
    (experiments: LogExperiment[]) => {
      if (!experiments.length) return;

      const names = experiments
        .map((e) => e.name)
        .filter(Boolean)
        .sort();
      const plainDatasetId = toPlainDatasetId(datasetId ?? null);
      const count = experiments.length;
      const isSingleExperiment = count === 1;

      toast({
        title: "Run complete",
        description: `${count} ${
          isSingleExperiment ? "experiment" : "experiments"
        } created${names.length ? `: ${names.join(" • ")}` : ""}`,
        actions:
          plainDatasetId && activeProjectId
            ? [
                <ToastAction
                  key="compare"
                  variant="link"
                  size="sm"
                  className="px-0"
                  altText={
                    isSingleExperiment
                      ? "View experiment"
                      : "Compare experiments"
                  }
                  onClick={() =>
                    window.open(
                      generateCompareExperimentsURL(
                        workspaceName,
                        activeProjectId,
                        plainDatasetId,
                        experiments.map((e) => e.id),
                      ),
                      "_blank",
                    )
                  }
                >
                  {isSingleExperiment ? "View experiment" : "Compare"}
                  <ExternalLink className="ml-1 size-3.5 shrink-0" />
                </ToastAction>,
              ]
            : undefined,
      });
    },
    [datasetId, workspaceName, activeProjectId, toast],
  );

  return useCallback(
    (experiments: LogExperiment[]) => {
      if (experiments.every((e) => e.name)) {
        announce(experiments);
        return;
      }

      Promise.all(
        experiments.map((experiment) =>
          withServerName(queryClient, experiment),
        ),
      ).then(announce);
    },
    [announce, queryClient],
  );
};

export default useRunCompletionToast;
