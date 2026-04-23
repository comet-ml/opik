import { useRef, useState } from "react";
import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PROJECTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Project } from "@/types/projects";
import { DEMO_PROJECT_NAME } from "@/constants/shared";

type UseDemoProjectParams = {
  workspaceName: string;
  // Enable polling while the demo project is missing. Demo data creation on
  // user signup is asynchronous on the backend — the `post_user_signup`
  // endpoint returns immediately while `create_demo_data()` runs in the
  // background for ~30–60s. Callers that render during onboarding should
  // set this so the "View Demo project" button surfaces as soon as the
  // project is ready, without a page reload. Off by default so ambient
  // usages outside onboarding don't keep a background poll running.
  poll?: boolean;
};

const POLL_INTERVAL_MS = 5000;
const MAX_POLL_DURATION_MS = 5 * 60 * 1000;

const getDemoProject = async ({ signal }: QueryFunctionContext) => {
  try {
    const { data } = await api.post<Project | null>(
      `${PROJECTS_REST_ENDPOINT}retrieve`,
      {
        name: DEMO_PROJECT_NAME,
      },
      {
        signal,
      },
    );

    return data ?? null;
  } catch (e) {
    return null;
  }
};

export default function useDemoProject(
  { workspaceName, poll = false }: UseDemoProjectParams,
  options?: QueryConfig<Project | null>,
) {
  // `query.state.dataUpdatedAt` resets on every successful fetch (even null),
  // so deriving the cap from it would never trigger. Track start time in a ref.
  const pollStartRef = useRef<number | null>(null);
  const [pollExpired, setPollExpired] = useState(false);

  const query = useQuery({
    queryKey: ["project", { workspaceName }],
    queryFn: (context) => getDemoProject(context),
    refetchInterval: poll
      ? (q) => {
          if (q.state.data) {
            pollStartRef.current = null;
            return false;
          }
          if (pollStartRef.current === null) {
            pollStartRef.current = Date.now();
          }
          if (Date.now() - pollStartRef.current > MAX_POLL_DURATION_MS) {
            setPollExpired(true);
            return false;
          }
          return POLL_INTERVAL_MS;
        }
      : undefined,
    ...options,
  });

  return { ...query, pollExpired };
}
