import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { WELCOME_WIZARD_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { WelcomeWizardTracking } from "@/types/welcome-wizard";

export const WELCOME_WIZARD_QUERY_KEY = "welcome-wizard";

const getWelcomeWizardStatus = async ({ signal }: QueryFunctionContext) => {
  const { data } = await api.get<WelcomeWizardTracking>(
    WELCOME_WIZARD_REST_ENDPOINT,
    {
      signal,
    },
  );

  return data;
};

export default function useWelcomeWizardStatus(
  options?: QueryConfig<WelcomeWizardTracking>,
) {
  return useQuery({
    queryKey: [WELCOME_WIZARD_QUERY_KEY, {}],
    queryFn: (context) => getWelcomeWizardStatus(context),
    ...options,
  });
}
