import { useMutation } from "@tanstack/react-query";
import { AxiosError } from "axios";

import api, { PROVIDER_KEYS_REST_ENDPOINT } from "@/api/api";
import { ProviderAuthConfig } from "@/types/providers";

export type ProviderAuthCheckRequest = {
  provider_id?: string;
  auth_config?: ProviderAuthConfig;
};

export type ProviderAuthCheckResult = {
  lifetime_seconds: number;
};

/**
 * Test-connection for dynamic token auth: the backend runs the recipe once and reports the token
 * lifetime — the token itself is never returned. Errors carry the upstream auth service's status
 * and body (credential values redacted), meant to be shown to the user verbatim.
 */
const useProviderKeysAuthCheckMutation = () => {
  return useMutation<
    ProviderAuthCheckResult,
    AxiosError,
    ProviderAuthCheckRequest
  >({
    mutationFn: async (request: ProviderAuthCheckRequest) => {
      const { data } = await api.post(
        `${PROVIDER_KEYS_REST_ENDPOINT}auth-config/test`,
        request,
      );
      return data;
    },
  });
};

export default useProviderKeysAuthCheckMutation;
