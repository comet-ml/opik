import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  OPEN_SOURCE_WELCOME_WIZARD_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { OpenSourceWelcomeWizardTracking } from "@/types/open-source-welcome-wizard";

export const OPEN_SOURCE_WELCOME_WIZARD_QUERY_KEY =
  "open-source-welcome-wizard";

type UseOpenSourceWelcomeWizardStatusParams = {
  workspaceName: string;
};

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
  params: UseOpenSourceWelcomeWizardStatusParams,
  options?: QueryConfig<OpenSourceWelcomeWizardTracking>,
) {
  return useQuery({
    queryKey: [OPEN_SOURCE_WELCOME_WIZARD_QUERY_KEY, params],
    queryFn: (context) => getOpenSourceWelcomeWizardStatus(context),
    ...options,
  });
}
