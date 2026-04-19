import React, { useMemo } from "react";
import get from "lodash/get";

import useCompareExperimentsList from "@/api/datasets/useCompareExperimentsList";
import { COMPARE_EXPERIMENTS_MAX_PAGE_SIZE } from "@/constants/experiments";
import useAppStore from "@/store/AppStore";
import MarkdownPreview from "@/shared/MarkdownPreview/MarkdownPreview";

interface PlaygroundTestSuiteLastRunOutputProps {
  experimentId: string | undefined;
  datasetItemId: string;
  datasetId: string;
}

const PlaygroundTestSuiteLastRunOutput: React.FunctionComponent<
  PlaygroundTestSuiteLastRunOutputProps
> = ({ experimentId, datasetItemId, datasetId }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data } = useCompareExperimentsList(
    {
      workspaceName,
      datasetId,
      experimentsIds: experimentId ? [experimentId] : [],
      page: 1,
      size: COMPARE_EXPERIMENTS_MAX_PAGE_SIZE,
      truncate: false,
    },
    {
      enabled: !!experimentId && !!datasetId,
      refetchInterval: (query) => {
        const rows = query.state.data?.content ?? [];
        const hasOutput = rows.some(
          (row) =>
            row.experiment_items?.some(
              (ei) => ei.dataset_item_id === datasetItemId,
            ),
        );
        return hasOutput ? false : 5000;
      },
    },
  );

  const { output, runCount } = useMemo(() => {
    const items = data?.content ?? [];
    const matchingRow = items.find(
      (item) =>
        item.experiment_items?.some(
          (ei) => ei.dataset_item_id === datasetItemId,
        ),
    );
    if (!matchingRow) return { output: null, runCount: 0 };

    const relatedItems =
      matchingRow.experiment_items
        ?.filter((ei) => ei.dataset_item_id === datasetItemId)
        .sort((a, b) => b.created_at.localeCompare(a.created_at)) ?? [];

    if (relatedItems.length === 0) return { output: null, runCount: 0 };

    const outputText = get(relatedItems[0], ["output", "output"], null);

    return {
      output: outputText != null ? String(outputText) : null,
      runCount: relatedItems.length,
    };
  }, [data?.content, datasetItemId]);

  if (!experimentId || output === null) {
    return null;
  }

  return (
    <div className="flex flex-col gap-1">
      {runCount > 1 && (
        <span className="comet-body-s text-muted-gray">Output (last run):</span>
      )}
      <MarkdownPreview>{output}</MarkdownPreview>
    </div>
  );
};

export default PlaygroundTestSuiteLastRunOutput;
