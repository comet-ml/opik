import { useQuery } from "@tanstack/react-query";
import { ALERT_EVENT_TYPE } from "@/types/alerts";
import { ALERTS_KEY, ALERTS_REST_ENDPOINT } from "@/api/api";
import api from "@/api/api";

export type WebhookExamplesResponse = {
  response_examples: Record<ALERT_EVENT_TYPE, string | object>;
};

const getWebhookExamples = async (): Promise<WebhookExamplesResponse> => {
  const { data } = await api.get<WebhookExamplesResponse>(
    `${ALERTS_REST_ENDPOINT}webhooks/examples`,
  );
  return data;
};

export default function useWebhookExamplesQuery() {
  return useQuery({
    queryKey: [ALERTS_KEY, "webhook-examples"],
    queryFn: getWebhookExamples,
  });
}
