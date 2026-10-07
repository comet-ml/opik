import React, { useCallback } from "react";
import { ExternalLink } from "lucide-react";

import { ToastAction } from "@/ui/toast";
import { useToast } from "@/ui/use-toast";
import { LogExperiment } from "@/types/playground";
import { generateCompareExperimentsURL } from "@/lib/experiments";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import { toPlainDatasetId } from "@/utils/datasetVersionStorage";

const useRunCompletionToast = (datasetId?: string | null) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const { toast } = useToast();

  return useCallback(
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
};

export default useRunCompletionToast;
