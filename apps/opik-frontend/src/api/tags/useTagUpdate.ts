import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Tag, TagUpdate } from "@/types/tags";
import { apiClient } from "@/lib/api-client";

interface UpdateTagParams {
  id: string;
  tagUpdate: TagUpdate;
}

const updateTag = async ({ id, tagUpdate }: UpdateTagParams): Promise<Tag> => {
  const response = await apiClient.put(`/v1/private/tags/${id}`, tagUpdate);
  return response.data;
};

export const useTagUpdate = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: updateTag,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["tags"] });
    },
  });
};