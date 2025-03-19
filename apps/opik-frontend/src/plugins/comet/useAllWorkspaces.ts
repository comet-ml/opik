import { useQuery } from "@tanstack/react-query";
import { QueryConfig } from "./api";
import { Workspace } from "./types";
import { uniqBy } from "lodash";
import useUserInvitedWorkspaces from "@/plugins/comet/useUserInvitedWorkspaces";
import useAdminOrganizationWorkspaces from "@/plugins/comet/useAdminOrganizationWorkspaces";

// all workspaces of organizations
export default function useAllWorkspaces(options?: QueryConfig<Workspace[]>) {
  const { data: userInvitedWorkspaces } = useUserInvitedWorkspaces(options);
  const { data: allUserWorkspaces } = useAdminOrganizationWorkspaces(options);

  return useQuery({
    queryKey: ["all-user-workspaces", { enabled: true }],
    queryFn: async () => {
      return !allUserWorkspaces || !userInvitedWorkspaces
        ? undefined
        : uniqBy(
            [...allUserWorkspaces, ...userInvitedWorkspaces],
            "workspaceId",
          );
    },
    enabled: !!allUserWorkspaces && !!userInvitedWorkspaces,
  });
}
