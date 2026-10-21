import { useQuery } from "@tanstack/react-query";
import { QueryConfig } from "@/api/api";
import {
  getPromptVersionsById,
  GetPromptVersionsByIdParams,
  PromptVersionsByIdResponse,
} from "./getPromptVersionsById";

export default function usePromptVersionsById(
  params: GetPromptVersionsByIdParams,
  options?: QueryConfig<PromptVersionsByIdResponse>,
) {
  return useQuery({
    queryKey: ["prompt-versions", params],
    queryFn: (context) => getPromptVersionsById(context, params),
    ...options,
  });
}
