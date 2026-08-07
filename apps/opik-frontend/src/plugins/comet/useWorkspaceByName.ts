import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import { QueryConfig } from "./api";
import { Workspace } from "./types";
import {
  WORKSPACE_POINT_READ_QUERY_OPTIONS,
  postWorkspacePointRead,
} from "./lib/workspacePointRead";

type RetrieveWorkspaceParams = {
  workspaceName: string;
};

const getWorkspaceByName = ({ signal, queryKey }: QueryFunctionContext) => {
  const { workspaceName } = queryKey[1] as RetrieveWorkspaceParams;
  return postWorkspacePointRead(
    "/workspaces/retrieve",
    { workspaceName },
    signal,
  );
};

export default function useWorkspaceByName(
  params: RetrieveWorkspaceParams,
  options?: QueryConfig<Workspace | null>,
) {
  return useQuery({
    queryKey: ["workspace", params],
    queryFn: getWorkspaceByName,
    ...WORKSPACE_POINT_READ_QUERY_OPTIONS,
    ...options,
    enabled: Boolean(params.workspaceName) && (options?.enabled ?? true),
  });
}
