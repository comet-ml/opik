import React, { useCallback } from "react";
import { ExternalLink } from "lucide-react";

import { ToastAction } from "@/ui/toast";
import { useToast } from "@/ui/use-toast";
import { LogExperiment } from "@/types/playground";
import { getExperimentById } from "@/api/datasets/useExperimentById";
import { generateCompareExperimentsURL } from "@/lib/experiments";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import { toPlainDatasetId } from "@/utils/datasetVersionStorage";

const NAME_LOOKUP_TIMEOUT_MS = 3000;

const withServerName = async (
  experiment: LogExperiment,
): Promise<LogExperiment> => {
  if (experiment.name) return experiment;

  try {
    const { name } = await getExperimentById(
      { signal: AbortSignal.timeout(NAME_LOOKUP_TIMEOUT_MS) },
      { experimentId: experiment.id },
    );
    return { ...experiment, name };
  } catch {
    return experiment;
  }
};

const useRunCompletionToast = (datasetId?: string | null) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const { toast } = useToast();

  const announce = useCallback(
    (experiments: LogExperiment[]) => {
      if (!experiments.length) return;

      const names = experiments
        .map((e) => e.name)
        .filter(Boolean)
        .sort();
      const plainDatasetId = toPlainDatasetId(datasetId ?? null);
      const count = experiments.length;

      toast({
        title: "Run complete",
        description: `${count} ${
          count === 1 ? "experiment" : "experiments"
        } created${names.length ? `: ${names.join(" • ")}` : ""}`,
        actions:
          plainDatasetId && activeProjectId
            ? [
                <ToastAction
                  key="compare"
                  variant="link"
                  size="sm"
                  className="px-0"
                  altText="Compare experiments"
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
                  Compare
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

      Promise.all(experiments.map(withServerName)).then(announce);
    },
    [announce],
  );
};

export default useRunCompletionToast;
