import { useQuery } from "@tanstack/react-query";
import uniqBy from "lodash/uniqBy";
import { QueryConfig } from "./api";
import { Workspace } from "./types";
import useUserWorkspacesLite from "@/plugins/comet/useUserWorkspacesLite";
import useUserInvitedWorkspaces from "@/plugins/comet/useUserInvitedWorkspaces";
import useAdminOrganizationWorkspaces from "@/plugins/comet/useAdminOrganizationWorkspaces";

export default function useAllWorkspaces(options?: QueryConfig<Workspace[]>) {
  const enabled = Boolean(options?.enabled);
  const lite = useUserWorkspacesLite({ enabled });

  const fallbackEnabled = enabled && lite.isError;

  const { data: userInvitedWorkspaces } = useUserInvitedWorkspaces({
    enabled: fallbackEnabled,
  });
  const { data: adminOrgWorkspaces } = useAdminOrganizationWorkspaces({
    enabled: fallbackEnabled,
  });

  const hasLite = lite.data !== undefined;
  const hasFallback =
    lite.isError && !!userInvitedWorkspaces && !!adminOrgWorkspaces;

  return useQuery({
    queryKey: ["all-user-workspaces", { enabled: true }],
    queryFn: async () => {
      if (hasLite) return lite.data;
      if (hasFallback) {
        return uniqBy(
          [...adminOrgWorkspaces, ...userInvitedWorkspaces],
          "workspaceId",
        );
      }
      return undefined;
    },
    enabled: hasLite || hasFallback,
  });
}
