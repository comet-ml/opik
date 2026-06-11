import { useQuery } from "@tanstack/react-query";
import { QueryConfig, SIGNALS_ISSUES_KEY } from "@/api/api";
import {
  ISSUE_SEVERITY,
  ISSUE_STATUS,
  IssuesListResponse,
  SIGNALS_SORT,
} from "@/types/signals";
import { MOCK_ISSUES, mockDelay } from "@/api/signals/mockSignalsData";

type UseProjectIssuesListParams = {
  projectId: string;
  status?: ISSUE_STATUS;
  sort?: SIGNALS_SORT;
};

const SEVERITY_ORDER: Record<ISSUE_SEVERITY, number> = {
  [ISSUE_SEVERITY.high]: 0,
  [ISSUE_SEVERITY.medium]: 1,
  [ISSUE_SEVERITY.low]: 2,
};

// TODO(signals-backend): replace the mock body with a real request, e.g.
//   const { data } = await api.get<IssuesListResponse>(
//     `${PROJECTS_REST_ENDPOINT}${projectId}/signals/issues`,
//     { signal, params: { status, sort } },
//   );
//   return data;
const getProjectIssuesList = async ({
  status = ISSUE_STATUS.open,
  sort = SIGNALS_SORT.severity,
}: UseProjectIssuesListParams): Promise<IssuesListResponse> => {
  const content = MOCK_ISSUES.filter((issue) => issue.status === status).sort(
    (a, b) => {
      switch (sort) {
        case SIGNALS_SORT.occurrences:
          return b.occurrences - a.occurrences;
        case SIGNALS_SORT.last_seen:
          return b.last_seen_at.localeCompare(a.last_seen_at);
        case SIGNALS_SORT.first_seen:
          return b.first_seen_at.localeCompare(a.first_seen_at);
        case SIGNALS_SORT.severity:
        default:
          return SEVERITY_ORDER[a.severity] - SEVERITY_ORDER[b.severity];
      }
    },
  );

  return mockDelay({
    content,
    total: content.length,
    sortable_by: Object.values(SIGNALS_SORT),
  });
};

export default function useProjectIssuesList(
  params: UseProjectIssuesListParams,
  options?: QueryConfig<IssuesListResponse>,
) {
  return useQuery({
    queryKey: [SIGNALS_ISSUES_KEY, params],
    queryFn: () => getProjectIssuesList(params),
    ...options,
  });
}
