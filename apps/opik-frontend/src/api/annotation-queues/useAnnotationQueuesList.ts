import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  ANNOTATION_QUEUES_REST_ENDPOINT,
  ANNOTATION_QUEUES_KEY,
  QueryConfig,
} from "@/api/api";
import {
  AnnotationQueue,
  ANNOTATION_QUEUE_SCOPE,
} from "@/types/annotation-queues";
import { Filters } from "@/types/filters";
import { Sorting } from "@/types/sorting";
import { processFilters } from "@/lib/filters";
import { processSorting } from "@/lib/sorting";

type UseAnnotationQueuesListParams = {
  filters?: Filters;
  sorting?: Sorting;
  search?: string;
  page?: number;
  size?: number;
};

export type UseAnnotationQueuesListResponse = {
  content: AnnotationQueue[];
  sortable_by: string[];
  total: number;
};

const getAnnotationQueuesList = async (
  { signal }: QueryFunctionContext,
  { filters, sorting, search, page, size }: UseAnnotationQueuesListParams,
) => {
  const { data } = await api.get<UseAnnotationQueuesListResponse>(
    ANNOTATION_QUEUES_REST_ENDPOINT,
    {
      signal,
      params: {
        ...processFilters(filters),
        ...processSorting(sorting),
        ...(search && { name: search }),
        ...(page && { page }),
        ...(size && { size }),
      },
    },
  );

  // TODO lala: remove mock data when backend is ready
  const mockData: UseAnnotationQueuesListResponse = {
    content: [
      {
        id: "ann_queue_7f3e4d2a-8b1c-4f6e-9a2d-5c8b7e4f1a3d",
        project_id: "11111c4e-8b5f-7239-a123-456789abcdef",
        project_name: "Example project",
        name: "Product Feedback Analysis Queue",
        description:
          "Queue for analyzing customer feedback traces related to product satisfaction and feature requests",
        scope: ANNOTATION_QUEUE_SCOPE.TRACE,
        instructions:
          "Please review each trace for sentiment, categorize the feedback type (bug report, feature request, compliment), and rate the urgency level. Focus on identifying actionable insights that can improve our product.",
        comments_enabled: true,
        feedback_definitions: ["01932c4e-8b5f-7239-a123-456789abcdef"],
        reviewers: [
          {
            username: "sarah.johnson",
            status: 23,
          },
        ],
        feedback_scores: [
          {
            name: "sentiment_analysis",
            value: 0.72,
          },
        ],
        items_count: 156,
        created_at: "2024-01-15T09:30:45.123Z",
        created_by: "admin@company.com",
        last_updated_at: "2024-01-18T14:22:17.456Z",
        last_updated_by: "sarah.johnson",
        last_scorred_at: "2024-01-18T16:45:32.789Z",
      },
    ],
    sortable_by: [
      "id",
      "name",
      "description",
      "instructions",
      "scope",
      "items_count",
      "created_at",
      "created_by",
      "last_updated_at",
      "last_updated_by",
      "last_scorred_at",
    ],
    total: 1,
  };

  return data ? data : mockData;
};

export default function useAnnotationQueuesList(
  params: UseAnnotationQueuesListParams,
  options?: QueryConfig<UseAnnotationQueuesListResponse>,
) {
  return useQuery({
    queryKey: [ANNOTATION_QUEUES_KEY, params],
    queryFn: (context) => getAnnotationQueuesList(context, params),
    ...options,
  });
}
