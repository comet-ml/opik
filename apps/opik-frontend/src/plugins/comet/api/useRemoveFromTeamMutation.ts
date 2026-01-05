import { useMutation, useQueryClient } from "@tanstack/react-query";
import api from "../api";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";

export interface RemoveFromTeamVariables {
  teamId: string;
  userName: string;
}

const REMOVE_FROM_TEAM_ENDPOINT = "/workspaces/removeFromTeam";

const extractServerMessage = (error: unknown): string => {
  const axiosError = error as AxiosError<{ message?: string; msg?: string }>;
  return (
    axiosError?.response?.data?.message ||
    axiosError?.response?.data?.msg ||
    "Failed to remove user from team"
  );
};

async function removeFromTeamRequest(variables: RemoveFromTeamVariables) {
  const { data } = await api.post(REMOVE_FROM_TEAM_ENDPOINT, {
    teamId: variables.teamId,
    userName: variables.userName,
  });

  return data;
}

export function useRemoveFromTeamMutation() {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationKey: ["workspace", "remove-from-team"],
    mutationFn: removeFromTeamRequest,
    onSuccess: (_, variables) => {
      toast({ description: "User removed from team successfully" });
      queryClient.invalidateQueries({
        queryKey: ["workspace-members", { workspaceId: variables.teamId }],
      });
      queryClient.invalidateQueries({
        queryKey: [
          "workspace-email-invites",
          { workspaceId: variables.teamId },
        ],
      });
      queryClient.invalidateQueries({
        queryKey: ["workspace-permissions", { workspaceId: variables.teamId }],
      });
    },
    onError: (error) => {
      const message = extractServerMessage(error);
      toast({ description: message, variant: "destructive" });
    },
  });
}
