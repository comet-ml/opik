import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  OPEN_SOURCE_WELCOME_WIZARD_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { OpenSourceWelcomeWizardTracking } from "@/types/open-source-welcome-wizard";

export const OPEN_SOURCE_WELCOME_WIZARD_QUERY_KEY =
  "open-source-welcome-wizard";

const getOpenSourceWelcomeWizardStatus = async ({
  signal,
}: QueryFunctionContext) => {
  const { data } = await api.get<OpenSourceWelcomeWizardTracking>(
    OPEN_SOURCE_WELCOME_WIZARD_REST_ENDPOINT,
    {
      signal,
    },
  );

  return data;
};

export default function useOpenSourceWelcomeWizardStatus(
  options?: QueryConfig<OpenSourceWelcomeWizardTracking>,
) {
  return useQuery({
    queryKey: [OPEN_SOURCE_WELCOME_WIZARD_QUERY_KEY, {}],
    queryFn: (context) => getOpenSourceWelcomeWizardStatus(context),
    ...options,
  });
}
