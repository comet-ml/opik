import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig } from "./api";

type UseWorkspaceEmailInvitesParams = {
  workspaceId: string;
};

interface Invite {
  inviteEmail: string;
  claimed: boolean;
  teamId: string;
}

interface UseWorkspaceEmailInvitesResponse {
  invites: Invite[];
}

interface InvitedMember {
  email: string;
  isMember: boolean;
}

const mapInvites = (invites: Invite[]): InvitedMember[] =>
  invites
    .filter(({ claimed }) => !claimed)
    .map(({ inviteEmail }) => ({ email: inviteEmail, isMember: false }));

const getWorkspaceEmailInvites = async (
  { signal }: QueryFunctionContext,
  { workspaceId }: UseWorkspaceEmailInvitesParams,
) => {
  const { data } = await api.get<UseWorkspaceEmailInvitesResponse>(
    "/workspaces/list-email-invites",
    {
      signal,
      params: {
        teamId: workspaceId,
      },
    },
  );

  return mapInvites(data?.invites || []);
};

export default function useWorkspaceEmailInvites(
  params: UseWorkspaceEmailInvitesParams,
  options?: QueryConfig<InvitedMember[]>,
) {
  return useQuery({
    queryKey: ["workspace-email-invites", params],
    queryFn: (context) => getWorkspaceEmailInvites(context, params),
    ...options,
  });
}
