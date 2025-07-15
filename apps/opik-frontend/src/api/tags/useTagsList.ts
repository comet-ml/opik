import { useQuery } from "@tanstack/react-query";
import { Tag } from "@/types/tags";
import { apiClient } from "@/lib/api-client";

const fetchTags = async (): Promise<Tag[]> => {
  const response = await apiClient.get("/v1/private/tags");
  return response.data;
};

export const useTagsList = () => {
  return useQuery({
    queryKey: ["tags"],
    queryFn: fetchTags,
  });
};