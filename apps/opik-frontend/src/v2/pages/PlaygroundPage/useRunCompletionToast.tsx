import React, { useCallback } from "react";
import { ExternalLink } from "lucide-react";

import { ToastAction } from "@/ui/toast";
import { useToast } from "@/ui/use-toast";
import { LogExperiment } from "@/types/playground";
import {
  useExperimentName,
  useLastSuggestedExperimentName,
  useSetSuggestedExperimentName,
} from "@/store/PlaygroundStore";
import { suggestNextExperimentName } from "@/lib/experiments";
import { useNavigateToExperiment } from "@/v2/pages-shared/experiments/useNavigateToExperiment";
import { toPlainDatasetId } from "@/utils/datasetVersionStorage";

const useRunCompletionToast = (datasetId?: string | null) => {
  const { navigate } = useNavigateToExperiment();
  const { toast } = useToast();
  const experimentName = useExperimentName();
  const lastSuggestedExperimentName = useLastSuggestedExperimentName();
  const setSuggestedExperimentName = useSetSuggestedExperimentName();

  return useCallback(
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
        actions: plainDatasetId
          ? [
              <ToastAction
                key="compare"
                variant="link"
                size="sm"
                className="px-0"
                altText="Compare experiments"
                onClick={() =>
                  navigate({
                    experimentIds: experiments.map((e) => e.id),
                    datasetId: plainDatasetId,
                  })
                }
              >
                Compare
                <ExternalLink className="ml-1 size-3.5 shrink-0" />
              </ToastAction>,
            ]
          : undefined,
      });

      if (experimentName) {
        setSuggestedExperimentName(
          suggestNextExperimentName(
            experimentName,
            lastSuggestedExperimentName,
          ),
        );
      }
    },
    [
      datasetId,
      navigate,
      toast,
      experimentName,
      lastSuggestedExperimentName,
      setSuggestedExperimentName,
    ],
  );
};

export default useRunCompletionToast;
