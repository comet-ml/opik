import { useQuery } from "@tanstack/react-query";
import { QueryConfig } from "@/api/api";
import { ALERT_EVENT_TYPE, ALERT_TYPE } from "@/types/alerts";
import { ALERTS_KEY, ALERTS_REST_ENDPOINT } from "@/api/api";
import api from "@/api/api";

export type WebhookExamplesResponse = {
  response_examples: Record<ALERT_EVENT_TYPE, string | object>;
};

type UseWebhookExamplesQueryParams = {
  alertType?: ALERT_TYPE;
};

const getWebhookExamples = async (
  params: UseWebhookExamplesQueryParams,
): Promise<WebhookExamplesResponse> => {
  const { data } = await api.get<WebhookExamplesResponse>(
    `${ALERTS_REST_ENDPOINT}webhooks/examples`,
    {
      params: params.alertType ? { alert_type: params.alertType } : undefined,
    },
  );
  return data;
};

export default function useWebhookExamplesQuery(
  params: UseWebhookExamplesQueryParams = {},
  options?: QueryConfig<WebhookExamplesResponse>,
) {
  return useQuery({
    queryKey: [ALERTS_KEY, { ...params, type: "webhook-examples" }],
    queryFn: () => getWebhookExamples(params),
    ...options,
  });
}
