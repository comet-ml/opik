import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import { QueryConfig } from "./api";
import { Workspace } from "./types";
import {
  WORKSPACE_POINT_READ_QUERY_OPTIONS,
  postWorkspacePointRead,
} from "./lib/workspacePointRead";

type RetrieveLandingWorkspaceParams = {
  organizationId: string;
};

const getLandingWorkspace = ({ signal, queryKey }: QueryFunctionContext) => {
  const { organizationId } = queryKey[1] as RetrieveLandingWorkspaceParams;
  return postWorkspacePointRead(
    "/workspaces/retrieve-landing",
    { organizationId },
    signal,
  );
};

// Where the user lands in an organization, by the backend's rule: their own default workspace if it
// is there, else the first they are a member of, else -- for an organization admin -- the
// organization's first. The last resort when a user's default workspace no longer resolves.
export default function useLandingWorkspace(
  params: RetrieveLandingWorkspaceParams,
  options?: QueryConfig<Workspace | null>,
) {
  return useQuery({
    queryKey: ["landing-workspace", params],
    queryFn: getLandingWorkspace,
    ...WORKSPACE_POINT_READ_QUERY_OPTIONS,
    ...options,
    enabled: Boolean(params.organizationId) && (options?.enabled ?? true),
  });
}
