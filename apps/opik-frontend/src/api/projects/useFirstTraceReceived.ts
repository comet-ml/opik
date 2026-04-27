import { useEffect, useRef, useState } from "react";
import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PROJECTS_KEY, PROJECTS_REST_ENDPOINT } from "@/api/api";
import { Project } from "@/types/projects";
import { DEMO_PROJECT_NAME } from "@/constants/shared";

type UseFirstTraceReceivedParams = {
  workspaceName: string;
  poll?: boolean;
  enabled?: boolean;
};

type ProjectsResponse = {
  content: Project[];
  total: number;
};

const POLL_INTERVAL_MS = 5000;
const MAX_POLL_DURATION_MS = 5 * 60 * 1000;

const getProjects = async (
  { signal }: QueryFunctionContext,
  workspaceName: string,
) => {
  const { data } = await api.get<ProjectsResponse>(PROJECTS_REST_ENDPOINT, {
    signal,
    params: { workspace_name: workspaceName, size: 100, page: 1 },
  });
  return data;
};

const getFirstTracedProject = (data: ProjectsResponse | undefined) =>
  (data?.content ?? [])
    .filter((p) => p.name !== DEMO_PROJECT_NAME && !!p.last_updated_trace_at)
    .reduce<Project | null>(
      (latest, p) =>
        !latest || p.last_updated_trace_at! > latest.last_updated_trace_at!
          ? p
          : latest,
      null,
    );

export default function useFirstTraceReceived({
  workspaceName,
  poll = false,
  enabled = true,
}: UseFirstTraceReceivedParams) {
  const pollStartRef = useRef<number | null>(null);
  const [pollExpired, setPollExpired] = useState(false);

  useEffect(() => {
    if (poll) {
      setPollExpired(false);
      pollStartRef.current = null;
    }
  }, [poll]);

  const query = useQuery({
    queryKey: [PROJECTS_KEY, { workspaceName, purpose: "first-trace-check" }],
    queryFn: (context) => getProjects(context, workspaceName),
    enabled,
    refetchInterval: poll
      ? (q) => {
          if (getFirstTracedProject(q.state.data)) {
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
  });

  const firstTracedProject = getFirstTracedProject(query.data);
  return {
    hasTraces: !!firstTracedProject,
    firstTraceProjectId: firstTracedProject?.id ?? null,
    pollExpired,
  };
}
