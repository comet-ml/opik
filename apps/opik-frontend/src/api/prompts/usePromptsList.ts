import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {PROMPTS_REST_ENDPOINT, QueryConfig} from "@/api/api";
import {Prompt} from "@/types/prompts";

type UsePromptsListParams = {
  workspaceName: string;
  search?: string;
  page: number;
  size: number;
};

type UsePromptsListResponse = {
  content: Prompt[];
  total: number;
};

// ALEX
const FAKE_PROMPTS: Prompt[] = [
  {
    "id": "4338ec03-6ee9-4635-b071-77849626b948",
    "name": "Item 1123421342134341234812349123841239048132094812304123804301294",
    "description": "This is a description for Item 1.",
    "last_updated_at": "2022-04-15 00:00:00",
    "created_at": "2020-09-09 00:00:00",
    "versions_count": 6
  },
  {
    "id": "2ac76571-520f-4664-9ad5-1439492ad557",
    "name": "Item 2",
    "description": "This is a description for Item 2.",
    "last_updated_at": "2022-08-19 00:00:00",
    "created_at": "2021-10-27 00:00:00",
    "versions_count": 4
  },
  {
    "id": "3ba74f16-698f-4318-9427-d26403bc117b",
    "name": "Item 3",
    "description": "This is a description for Item 3.",
    "last_updated_at": "2023-12-12 00:00:00",
    "created_at": "2021-10-08 00:00:00",
    "versions_count": 5
  },
  {
    "id": "bbef9b6c-d991-4132-9c08-24e1584ccea2",
    "name": "Item 4",
    "description": "This is a description for Item 4.",
    "last_updated_at": "2023-05-14 00:00:00",
    "created_at": "2023-01-04 00:00:00",
    "versions_count": 10
  },
  {
    "id": "57c82e2b-3d75-4f16-87ac-3201554ea84d",
    "name": "Item 5",
    "description": "This is a description for Item 5.",
    "last_updated_at": "2022-06-10 00:00:00",
    "created_at": "2021-10-06 00:00:00",
    "versions_count": 4
  },
  {
    "id": "caad2d07-6be4-4e30-8e54-45928c4c675c",
    "name": "Item 6",
    "description": "This is a description for Item 6.",
    "last_updated_at": "2023-11-07 00:00:00",
    "created_at": "2022-04-26 00:00:00",
    "versions_count": 7
  },
  {
    "id": "955439b8-2199-4edb-bfff-0becef27f477",
    "name": "Item 7",
    "description": "This is a description for Item 7.",
    "last_updated_at": "2023-09-21 00:00:00",
    "created_at": "2023-08-15 00:00:00",
    "versions_count": 5
  },
  {
    "id": "059452b9-9050-4b99-893f-8e809adae877",
    "name": "Item 8",
    "description": "This is a description for Item 8.",
    "last_updated_at": "2023-04-10 00:00:00",
    "created_at": "2022-09-22 00:00:00",
    "versions_count": 8
  },
  {
    "id": "c46f907a-7e35-4d35-a482-fb4a7fee3967",
    "name": "Item 9",
    "description": "This is a description for Item 9.",
    "last_updated_at": "2023-12-22 00:00:00",
    "created_at": "2023-05-13 00:00:00",
    "versions_count": 8
  },
  {
    "id": "c4365771-f654-4434-9272-8e22f623906b",
    "name": "Item 10",
    "description": "This is a description for Item 10.",
    "last_updated_at": "2023-09-09 00:00:00",
    "created_at": "2022-12-02 00:00:00",
    "versions_count": 6
  }
]


const getPromptsList = async (
  { signal }: QueryFunctionContext,
  { workspaceName, search, size, page }: UsePromptsListParams,
) => {
  // const { data } = await api.get(PROMPTS_REST_ENDPOINT, {
  //   signal,
  //   params: {
  //     workspace_name: workspaceName,
  //     ...(search && { name: search }),
  //     size,
  //     page,
  //   },
  // });

  return {
    content: FAKE_PROMPTS,
    total: FAKE_PROMPTS.length,
  };
};

export default function usePromptsList(
  params: UsePromptsListParams,
  options?: QueryConfig<UsePromptsListResponse>,
) {
  return useQuery({
    queryKey: ["prompts", params],
    queryFn: (context) => getPromptsList(context, params),
    ...options,
  });
}
