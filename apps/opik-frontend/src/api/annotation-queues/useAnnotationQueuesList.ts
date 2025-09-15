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
import { generateProjectFilters, processFilters } from "@/lib/filters";
import { processSorting } from "@/lib/sorting";

type UseAnnotationQueuesListParams = {
  workspaceName?: string;
  filters?: Filters;
  sorting?: Sorting;
  projectId?: string;
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
  {
    filters,
    sorting,
    projectId,
    search,
    page,
    size,
  }: UseAnnotationQueuesListParams,
) => {
  const { data } = await api.get<UseAnnotationQueuesListResponse>(
    ANNOTATION_QUEUES_REST_ENDPOINT,
    {
      signal,
      params: {
        ...processFilters(filters, generateProjectFilters(projectId)),
        ...processSorting(sorting),
        ...(search && { name: search }),
        ...(page && { page }),
        ...(size && { size }),
      },
      validateStatus: (status) =>
        (status >= 200 && status < 300) || status === 404, // TODO lala
    },
  );

  // TODO lala: remove mock data when backend is ready
  const mockData: UseAnnotationQueuesListResponse = {
    content: [
      {
        id: "ann_queue_7f3e4d2a-8b1c-4f6e-9a2d-5c8b7e4f1a3d",
        project_id: "0193da4f-16e9-75e5-a5af-39a609f63477",
        project_name: "playground",
        name: "Product Feedback Analysis Queue",
        description:
          "Queue for analyzing customer feedback traces related to product satisfaction and feature requests",
        scope: ANNOTATION_QUEUE_SCOPE.TRACE,
        instructions:
          "Please review each trace for sentiment, categorize the feedback type (bug report, feature request, compliment), and rate the urgency level. Focus on identifying actionable insights that can improve our product.",
        comments_enabled: true,
        feedback_definition_names: ["sentiment_analysis"],
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
      {
        id: "ann_queue_9k2m1n5p-6c8d-7e4a-3f9b-2a1c5e8d9f0b",
        project_id: "0193da4f-16e9-75e5-a5af-39a609f63477",
        project_name: "playground",
        name: "Conversation Quality Review Queue",
        description:
          "Queue for reviewing customer support conversation threads to ensure quality and consistency in responses",
        scope: ANNOTATION_QUEUE_SCOPE.THREAD,
        instructions:
          "Please evaluate each conversation thread for response quality, customer satisfaction, and adherence to support guidelines. Rate the helpfulness of responses and identify any missed opportunities for better customer service.",
        comments_enabled: true,
        feedback_definition_names: [
          "response_quality",
          "customer_satisfaction",
        ],
        reviewers: [
          {
            username: "mike.chen",
            status: 45,
          },
          {
            username: "lisa.wang",
            status: 28,
          },
        ],
        feedback_scores: [
          {
            name: "response_quality",
            value: 0.85,
          },
          {
            name: "customer_satisfaction",
            value: 0.78,
          },
        ],
        items_count: 89,
        created_at: "2024-01-12T11:15:30.456Z",
        created_by: "admin@company.com",
        last_updated_at: "2024-01-19T10:30:42.123Z",
        last_updated_by: "mike.chen",
        last_scorred_at: "2024-01-19T15:20:18.567Z",
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
    total: 2,
  };

  return data?.content ? data : mockData;
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
