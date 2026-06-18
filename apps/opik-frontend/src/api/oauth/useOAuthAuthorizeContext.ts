import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  OAUTH_AUTHORIZE_CONTEXT_KEY,
  OAUTH_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { OAuthAuthorizeContext } from "@/api/oauth/types";

type UseOAuthAuthorizeContextParams = {
  client_id: string;
  redirect_uri: string;
};

const getOAuthAuthorizeContext = async (
  { signal }: QueryFunctionContext,
  params: UseOAuthAuthorizeContextParams,
) => {
  const { data } = await api.get<OAuthAuthorizeContext>(
    `${OAUTH_REST_ENDPOINT}authorize/context`,
    { signal, params },
  );
  return data;
};

export default function useOAuthAuthorizeContext(
  params: UseOAuthAuthorizeContextParams,
  options?: QueryConfig<OAuthAuthorizeContext>,
) {
  return useQuery({
    queryKey: [OAUTH_AUTHORIZE_CONTEXT_KEY, params],
    queryFn: (context) => getOAuthAuthorizeContext(context, params),
    // 4xx here is terminal (invalid client / redirect / no session) — retry can't recover.
    retry: false,
    ...options,
  });
}
