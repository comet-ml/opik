import { useMutation } from "@tanstack/react-query";
import api, { OAUTH_REST_ENDPOINT } from "@/api/api";
import { OAuthConsentRequest, OAuthConsentResponse } from "@/api/oauth/types";

const useOAuthConsentMutation = () =>
  useMutation({
    mutationFn: async (
      body: OAuthConsentRequest,
    ): Promise<OAuthConsentResponse> => {
      const { data } = await api.post<OAuthConsentResponse>(
        `${OAUTH_REST_ENDPOINT}authorize`,
        body,
      );
      return data;
    },
  });

export default useOAuthConsentMutation;
