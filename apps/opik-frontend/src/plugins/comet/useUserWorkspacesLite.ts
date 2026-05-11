import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import { AxiosError } from "axios";
import api, { QueryConfig } from "./api";
import { Workspace } from "./types";

export type LiteWorkspacesResult =
  | { kind: "data"; data: Workspace[] }
  | { kind: "unsupported" };

const getUserWorkspacesLite = async ({
  signal,
}: QueryFunctionContext): Promise<LiteWorkspacesResult> => {
  try {
    const response = await api.get<Workspace[]>(`/workspaces/lite`, { signal });
    return { kind: "data", data: response.data };
  } catch (error) {
    if ((error as AxiosError)?.response?.status === 404) {
      return { kind: "unsupported" };
    }
    throw error;
  }
};

export default function useUserWorkspacesLite(
  options?: QueryConfig<LiteWorkspacesResult>,
) {
  return useQuery({
    queryKey: ["user-workspaces-lite", { enabled: true }],
    queryFn: getUserWorkspacesLite,
    staleTime: Infinity,
    ...options,
    enabled: Boolean(options?.enabled),
  });
}
