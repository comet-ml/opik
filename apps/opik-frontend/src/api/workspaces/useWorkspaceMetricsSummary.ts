import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig, WORKSPACES_REST_ENDPOINT } from "@/api/api";
import { WorkspaceMetricSummary } from "@/types/workspaces";

type UseWorkspaceMetricsSummaryParams = {
  projectIds: string[];
  intervalStart: string;
  intervalEnd: string;
};

interface WorkspaceMetricsSummaryResponse {
  results: WorkspaceMetricSummary[];
}

const getWorkspaceMetricsSummary = async (
  { signal }: QueryFunctionContext,
  { projectIds, intervalStart, intervalEnd }: UseWorkspaceMetricsSummaryParams,
) => {
  const { data } = await api.post<WorkspaceMetricsSummaryResponse>(
    `${WORKSPACES_REST_ENDPOINT}metrics/summaries`,
    {
      ...(projectIds.length > 0 && { project_ids: projectIds }),
      interval_start: intervalStart,
      interval_end: intervalEnd,
    },
    {
      signal,
      validateStatus: (status) => status === 200 || status === 404, // TODO lala delete this line when backend is ready
    },
  );

  // Simulate network delay for demo purposes
  await new Promise((resolve) =>
    setTimeout(resolve, Math.floor(Math.random() * (3000 - 200 + 1)) + 200),
  );

  // TODO lala remove mock data
  const metrics = [
    {
      name: "hallucination_metric",
      current: Math.random() < 0.2 ? null : Math.random() * 100,
      previous: Math.random() < 0.2 ? Math.random() * 100 : null,
    },
    {
      name: "equals_metric",
      current: Math.random() < 0.2 ? Math.random() * 100 : null,
      previous: Math.random() * 100,
    },
    {
      name: "contains_metric",
      current: Math.random() * 100,
      previous: Math.random() < 0.2 ? null : Math.random() * 100,
    },
    {
      name: "regex_match_metric",
      current: Math.random() < 0.2 ? null : Math.random() * 100,
      previous: Math.random() * 100,
    },
    {
      name: "is_json_metric",
      current: Math.random() * 100,
      previous: Math.random() < 0.2 ? null : Math.random() * 100,
    },
    {
      name: "levenshtein_ratio_metric",
      current: Math.random() * 100,
      previous: Math.random() < 0.2 ? null : Math.random() * 100,
    },
    {
      name: "sentence_bleu_metric",
      current: Math.random() < 0.2 ? Math.random() * 100 : null,
      previous: Math.random() * 100,
    },
    {
      name: "CorpusBleu",
      current: Math.random() * 100,
      previous: Math.random() < 0.2 ? null : Math.random() * 100,
    },
    {
      name: "rouge_metric",
      current: Math.random() < 0.2 ? null : Math.random() * 100,
      previous: Math.random() * 100,
    },
    {
      name: "Sentiment very long metric name just to test the UI",
      current: Math.random() * 100,
      previous: Math.random() < 0.2 ? null : Math.random() * 100,
    },
    {
      name: "Sentiment_very_long_metric_name_just_to_test_the_UI",
      current: Math.random() < 0.2 ? Math.random() * 100 : null,
      previous: Math.random() * 100,
    },
    {
      name: "g_eval_metric",
      current: Math.random() * 100,
      previous: Math.random() < 0.2 ? null : Math.random() * 100,
    },
    {
      name: "moderation_metric",
      current: Math.random() < 0.2 ? null : Math.random() * 100,
      previous: Math.random() * 100,
    },
    {
      name: "UsefulnessMetric",
      current: Math.random() * 100,
      previous: Math.random() < 0.2 ? null : Math.random() * 100,
    },
    {
      name: "RandomReason",
      current: Math.random() < 0.2 ? Math.random() * 100 : null,
      previous: Math.random() * 100,
    },
    {
      name: "context_precision_metric",
      current: Math.random() * 100,
      previous: Math.random() < 0.2 ? null : Math.random() * 100,
    },
    {
      name: "context_recall_metric",
      current: Math.random() < 0.2 ? null : Math.random() * 100,
      previous: Math.random() * 100,
    },
    {
      name: "answer_relevance_metric",
      current: Math.random() * 100,
      previous: Math.random() < 0.2 ? null : Math.random() * 100,
    },
    {
      name: "corpus_bleu_metric",
      current: Math.random() < 0.2 ? Math.random() * 100 : null,
      previous: Math.random() * 100,
    },
  ];

  return metrics.slice(0, Math.floor(Math.random() * metrics.length) + 1);

  return data?.results;
};

const useWorkspaceMetricsSummary = (
  params: UseWorkspaceMetricsSummaryParams,
  config?: QueryConfig<WorkspaceMetricSummary[]>,
) => {
  return useQuery({
    queryKey: ["workspace-metrics-summary", params],
    queryFn: (context) => getWorkspaceMetricsSummary(context, params),
    ...config,
  });
};

export default useWorkspaceMetricsSummary;
