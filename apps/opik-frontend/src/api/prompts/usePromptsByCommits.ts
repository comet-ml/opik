import { PromptCommitInfo } from "@/types/prompts";

type UsePromptsByCommitsParams = {
  commits: string[];
};

const MOCK_PROMPTS: Record<string, PromptCommitInfo> = {
  "system-prompt:v3": {
    prompt_version_id: "019c7c09-7b23-740b-8dc3-9bcad94d3e5d",
    commit: "system-prompt:v3",
    prompt_id: "019c7c09-7af2-73c2-8ff6-2ece843e9449",
    prompt_name: "System Prompt - Classifier",
  },
  "user-template:v12": {
    prompt_version_id: "019c4800-3c3d-762a-98c8-1f9332e6ed59",
    commit: "user-template:v12",
    prompt_id: "019c4800-3c4a-7559-81ff-ddaa34129d40",
    prompt_name: "User Template - Chat Assistant",
  },
};

// TODO: Replace mock with real API call
// const getPromptsByCommits = async (
//   { signal }: QueryFunctionContext,
//   { commits }: UsePromptsByCommitsParams,
// ) => {
//   const { data } = await api.post<PromptCommitInfo[]>(
//     `${PROMPTS_REST_ENDPOINT}retrieve-by-commits`,
//     { commits },
//     { signal },
//   );
//   return data;
// };

export default function usePromptsByCommits(
  params: UsePromptsByCommitsParams,
) {
  const data = params.commits.map(
    (commit) => MOCK_PROMPTS[commit] ?? { commit },
  );

  return {
    data,
    isPending: false,
  };
}
