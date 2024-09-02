import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig } from "./api";
import { User } from "./types";

const getUser = async ({ signal }: QueryFunctionContext) => {
  const { data } = await api.get<User>("/auth/test", {
    signal,
  });

  return data;
};

export default function useUser(options?: QueryConfig<User>) {
  return useQuery({
    queryKey: ["user", {}],
    queryFn: (context) => getUser(context),
    ...options,
  });
}
