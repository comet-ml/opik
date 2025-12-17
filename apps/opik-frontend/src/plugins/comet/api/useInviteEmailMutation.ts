import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";
import { capitalizeFirstLetter } from "@/lib/utils";
import api from "../api";

export type InviteEmailVariables = {
  workspaceId: string;
  email: string;
};

const INVITE_EMAIL_ENDPOINT = "/workspaces/invite-llm";

const extractServerMessage = (error: unknown): string => {
  const axiosError = error as AxiosError<{ msg: string }>;
  return capitalizeFirstLetter(
    axiosError?.response?.data?.msg || "Email invite failed",
  );
};

async function inviteEmailRequest(variables: InviteEmailVariables) {
  const { workspaceId, email } = variables;

  const { data } = await api.post(INVITE_EMAIL_ENDPOINT, {
    teamId: workspaceId,
    shareWithEmail: email,
  });

  return data;
}

export function useInviteEmailMutation() {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationKey: ["workspace", "invite-email"],
    mutationFn: inviteEmailRequest,
    onSuccess: (_, variables) => {
      toast({ description: "Email invite sent successfully" });
      queryClient.invalidateQueries({
        queryKey: [
          "workspace-email-invites",
          { workspaceId: variables.workspaceId },
        ],
      });
    },
    onError: (error) => {
      const message = extractServerMessage(error);
      toast({ description: message, variant: "destructive" });
    },
  });
}
