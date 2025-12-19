import { useMutation, useQueryClient } from "@tanstack/react-query";
import api from "../api";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";
import { z } from "zod";
import { capitalizeFirstLetter } from "@/lib/utils";

export type InviteUsersVariables = {
  workspaceId: string;
  users: string[];
};

const INVITE_USERS_ENDPOINT = "/workspaces/invite-usernames-llm";
const INVITE_EMAILS_ENDPOINT = "/workspaces/invite-llm";

const isEmail = (value: string): boolean =>
  z.string().email().safeParse(value).success;

const extractServerMessage = (error: unknown): string => {
  const axiosError = error as AxiosError<{ msg: string }>;
  return capitalizeFirstLetter(
    axiosError?.response?.data?.msg || "Invite request failed",
  );
};

const partitionUsers = (
  users: string[],
): { emails: string[]; usernames: string[] } => {
  const emails: string[] = [];
  const usernames: string[] = [];
  for (const value of users) {
    if (isEmail(value)) {
      emails.push(value);
    } else {
      usernames.push(value);
    }
  }

  return { emails, usernames };
};

async function getInviteUsernamesRequest(
  usernames: string[],
  workspaceId: string,
) {
  if (!usernames.length) return;

  return api.post(INVITE_USERS_ENDPOINT, {
    userNames: usernames,
    workspaceId,
  });
}

function getInviteEmailsRequests(emails: string[], workspaceId: string) {
  if (!emails.length) return [];

  return emails.map((email) =>
    api.post(INVITE_EMAILS_ENDPOINT, {
      teamId: workspaceId,
      shareWithEmail: email,
    }),
  );
}

async function inviteUsersRequest(variables: InviteUsersVariables) {
  const { users, workspaceId } = variables;
  const { emails, usernames } = partitionUsers(users);

  const allRequests = [
    getInviteUsernamesRequest(usernames, workspaceId),
    ...getInviteEmailsRequests(emails, workspaceId),
  ];

  const results = await Promise.allSettled(allRequests);
  const firstError = results.find(
    (result): result is PromiseRejectedResult => result.status === "rejected",
  );

  if (!firstError) {
    return {
      success: true,
    };
  }

  throw firstError.reason;
}

export function useInviteUsersMutation() {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationKey: ["workspace", "invite-users"],
    mutationFn: inviteUsersRequest,
    onSuccess: (_, variables) => {
      toast({ description: "Invite sent successfully" });
      queryClient.invalidateQueries({
        queryKey: ["workspace-members", { workspaceId: variables.workspaceId }],
      });
      queryClient.invalidateQueries({
        queryKey: [
          "workspace-email-invites",
          { workspaceId: variables.workspaceId },
        ],
      });
      queryClient.invalidateQueries({
        queryKey: [
          "workspace-permissions",
          { workspaceId: variables.workspaceId },
        ],
      });
    },
    onError: (error) => {
      const message = extractServerMessage(error) || "Invite request failed";
      toast({ description: message, variant: "destructive" });
    },
  });
}
