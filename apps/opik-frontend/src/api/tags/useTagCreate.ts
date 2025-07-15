import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Tag, TagCreate } from "@/types/tags";
import { apiClient } from "@/lib/api-client";

const createTag = async (tagCreate: TagCreate): Promise<Tag> => {
  const response = await apiClient.post("/v1/private/tags", tagCreate);
  return response.data;
};

export const useTagCreate = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: createTag,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["tags"] });
    },
  });
};