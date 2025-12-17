import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";
import { capitalizeFirstLetter } from "@/lib/utils";
import api from "../api";

export type InviteUsernameVariables = {
  workspaceId: string;
  userName: string;
};

const INVITE_USERNAME_ENDPOINT = "/workspaces/invite-usernames-llm";

const extractServerMessage = (error: unknown): string => {
  const axiosError = error as AxiosError<{ msg: string }>;
  return capitalizeFirstLetter(
    axiosError?.response?.data?.msg || "Username invite failed",
  );
};

async function inviteUsernameRequest(variables: InviteUsernameVariables) {
  const { workspaceId, userName } = variables;

  const { data } = await api.post(INVITE_USERNAME_ENDPOINT, {
    userNames: [userName],
    workspaceId,
  });

  return data;
}

export function useInviteUsernameMutation() {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationKey: ["workspace", "invite-username"],
    mutationFn: inviteUsernameRequest,
    onSuccess: (_, variables) => {
      toast({ description: "User invited successfully" });
      queryClient.invalidateQueries({
        queryKey: ["workspace-members", { workspaceId: variables.workspaceId }],
      });
    },
    onError: (error) => {
      const message = extractServerMessage(error);
      toast({ description: message, variant: "destructive" });
    },
  });
}
