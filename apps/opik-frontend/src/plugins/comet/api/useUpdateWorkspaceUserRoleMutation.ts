import { useMutation, useQueryClient } from "@tanstack/react-query";
import api from "../api";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";
import { WORKSPACE_USERS_ROLES_QUERY_KEY } from "./useWorkspaceUsersRoles";

export interface UpdateWorkspaceUserRoleVariables {
  userName: string;
  roleId: string;
  workspaceId: string;
}

const extractServerMessage = (error: unknown): string => {
  const axiosError = error as AxiosError<{ message?: string; msg?: string }>;
  return (
    axiosError?.response?.data?.message ||
    axiosError?.response?.data?.msg ||
    "Failed to update workspace user role."
  );
};

async function updateWorkspaceUserRoleRequest(
  variables: UpdateWorkspaceUserRoleVariables,
) {
  const { data } = await api.put(
    `/workspace-roles/user/${encodeURIComponent(variables.userName)}`,
    {
      roleId: variables.roleId,
      workspaceId: variables.workspaceId,
    },
  );

  return data;
}

export function useUpdateWorkspaceUserRoleMutation() {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationKey: ["workspace", "update-user-role"],
    mutationFn: updateWorkspaceUserRoleRequest,
    onSuccess: (_, variables) => {
      toast({ description: "User role has been successfully updated." });
      queryClient.invalidateQueries({
        queryKey: [
          WORKSPACE_USERS_ROLES_QUERY_KEY,
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
