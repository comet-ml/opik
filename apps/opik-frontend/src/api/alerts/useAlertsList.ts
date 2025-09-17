import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { ALERTS_KEY, ALERTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import {
  AlertsListResponse,
  Alert,
  ALERT_EVENT_TYPE,
  ALERT_CONDITION_TYPE,
} from "@/types/alerts";

type UseAlertsListParams = {
  workspaceName?: string;
  search?: string;
  page: number;
  size: number;
};

// Mock data for 11 alerts with distributed events
const MOCK_ALERTS: Alert[] = [
  {
    id: "alert-1",
    name: "Production Errors",
    enabled: true,
    url: "https://hooks.slack.com/services/T00000000/B00000000/XXXXXXXXXXXXXXXXXXXXXXXX",
    secret_token: "sk_live_abcd1234",
    created_at: "2024-01-15T10:30:00Z",
    created_by: "john.doe",
    last_updated_at: "2024-01-20T14:15:00Z",
    last_updated_by: "john.doe",
    events: [
      {
        event_type: ALERT_EVENT_TYPE.trace_errors,
        conditions: [
          {
            type: ALERT_CONDITION_TYPE.project_scope,
            value: ["proj-123", "proj-456"],
          },
        ],
      },
    ],
  },
  {
    id: "alert-2",
    name: "Production Guardrails",
    enabled: false,
    url: "https://hooks.slack.com/services/T00000000/B00000001/YYYYYYYYYYYYYYYYYYYYYYYY",
    created_at: "2024-01-12T09:20:00Z",
    created_by: "sarah.smith",
    last_updated_at: "2024-01-25T16:45:00Z",
    last_updated_by: "mike.wilson",
    events: [
      {
        event_type: ALERT_EVENT_TYPE.guardrails,
        conditions: [
          {
            type: ALERT_CONDITION_TYPE.value_threshold,
            lower_bound: 0.7,
            upper_bound: 1.0,
          },
        ],
      },
    ],
  },
  {
    id: "alert-3",
    name: "Critical Model Performance",
    enabled: true,
    url: "https://discord.com/api/webhooks/1234567890/abcdef123456",
    secret_token: "webhook_secret_123",
    created_at: "2024-01-18T11:45:00Z",
    created_by: "alex.johnson",
    last_updated_at: "2024-01-22T08:30:00Z",
    last_updated_by: "alex.johnson",
    events: [
      {
        event_type: ALERT_EVENT_TYPE.trace_score,
        conditions: [
          {
            type: ALERT_CONDITION_TYPE.value_threshold,
            lower_bound: 0,
            upper_bound: 0.5,
          },
        ],
      },
      {
        event_type: ALERT_EVENT_TYPE.trace_score,
        conditions: [
          {
            type: ALERT_CONDITION_TYPE.value_threshold,
            lower_bound: 0.8,
            upper_bound: 1.0,
          },
        ],
      },
    ],
  },
  {
    id: "alert-4",
    name: "New Prompt Deployments",
    enabled: true,
    url: "https://hooks.zapier.com/hooks/catch/12345/abcdef/",
    created_at: "2024-01-10T15:20:00Z",
    created_by: "emily.davis",
    last_updated_at: "2024-01-28T12:10:00Z",
    last_updated_by: "john.doe",
    events: [
      {
        event_type: ALERT_EVENT_TYPE.prompt_creation,
      },
      {
        event_type: ALERT_EVENT_TYPE.prompt_commit,
      },
    ],
  },
  {
    id: "alert-5",
    name: "Thread Quality Monitoring",
    enabled: false,
    url: "https://api.pagerduty.com/integration/v1/webhooks/abc123",
    secret_token: "pd_secret_xyz789",
    created_at: "2024-01-14T13:55:00Z",
    created_by: "david.brown",
    last_updated_at: "2024-01-26T17:40:00Z",
    last_updated_by: "sarah.smith",
    events: [
      {
        event_type: ALERT_EVENT_TYPE.thread_score,
        conditions: [
          {
            type: ALERT_CONDITION_TYPE.value_threshold,
            lower_bound: 0,
            upper_bound: 0.3,
          },
        ],
      },
      {
        event_type: ALERT_EVENT_TYPE.thread_score,
        conditions: [
          {
            type: ALERT_CONDITION_TYPE.value_threshold,
            lower_bound: 0,
            upper_bound: 0.4,
          },
        ],
      },
      {
        event_type: ALERT_EVENT_TYPE.thread_score,
      },
    ],
  },
  {
    id: "alert-6",
    name: "Development Environment Issues",
    enabled: true,
    url: "https://hooks.slack.com/services/T00000000/B00000002/ZZZZZZZZZZZZZZZZZZZZZZZZ",
    created_at: "2024-01-16T14:25:00Z",
    created_by: "lisa.martinez",
    last_updated_at: "2024-01-24T11:20:00Z",
    last_updated_by: "lisa.martinez",
    events: [
      {
        event_type: ALERT_EVENT_TYPE.trace_errors,
        conditions: [
          {
            type: ALERT_CONDITION_TYPE.project_scope,
            value: ["dev-proj-1", "staging-proj-1", "staging-proj-2"],
          },
        ],
      },
      {
        event_type: ALERT_EVENT_TYPE.trace_errors,
        conditions: [
          {
            type: ALERT_CONDITION_TYPE.project_scope,
            value: ["mobile-app-ios", "mobile-app-android"],
          },
        ],
      },
    ],
  },
  {
    id: "alert-7",
    name: "High Volume Guardrails",
    enabled: true,
    url: "https://webhook.site/#!/unique-id-here",
    created_at: "2024-01-11T16:40:00Z",
    created_by: "robert.lee",
    last_updated_at: "2024-01-29T09:15:00Z",
    last_updated_by: "emily.davis",
    events: [
      {
        event_type: ALERT_EVENT_TYPE.guardrails,
      },
      {
        event_type: ALERT_EVENT_TYPE.guardrails,
        conditions: [
          {
            type: ALERT_CONDITION_TYPE.project_scope,
            value: ["content-moderation-proj", "security-audit-proj"],
          },
        ],
      },
      {
        event_type: ALERT_EVENT_TYPE.guardrails,
        conditions: [
          {
            type: ALERT_CONDITION_TYPE.project_scope,
            value: ["gdpr-compliance", "hipaa-compliance"],
          },
        ],
      },
    ],
  },
  {
    id: "alert-8",
    name: "Model Drift Detection",
    enabled: false,
    url: "https://api.teams.microsoft.com/webhook/abc123/IncomingWebhook/def456",
    secret_token: "teams_webhook_secret",
    created_at: "2024-01-13T10:15:00Z",
    created_by: "michael.garcia",
    last_updated_at: "2024-01-27T15:50:00Z",
    last_updated_by: "david.brown",
    events: [
      {
        event_type: ALERT_EVENT_TYPE.trace_score,
        conditions: [
          {
            type: ALERT_CONDITION_TYPE.value_threshold,
            lower_bound: 0,
            upper_bound: 0.6,
          },
        ],
      },
      {
        event_type: ALERT_EVENT_TYPE.trace_score,
        conditions: [
          {
            type: ALERT_CONDITION_TYPE.value_threshold,
            lower_bound: 0.9,
            upper_bound: 1.0,
          },
        ],
      },
      {
        event_type: ALERT_EVENT_TYPE.trace_score,
        conditions: [
          {
            type: ALERT_CONDITION_TYPE.value_threshold,
            lower_bound: 0.7,
            upper_bound: 1.0,
          },
        ],
      },
    ],
  },
  {
    id: "alert-9",
    name: "Emergency Response Team",
    enabled: true,
    url: "https://hooks.slack.com/services/T00000000/B00000003/AAAAAAAAAAAAAAAAAAAAAAAA",
    secret_token: "emergency_alert_key",
    created_at: "2024-01-08T12:00:00Z",
    created_by: "admin",
    last_updated_at: "2024-01-30T18:45:00Z",
    last_updated_by: "admin",
    events: [
      {
        event_type: ALERT_EVENT_TYPE.trace_errors,
      },
      {
        event_type: ALERT_EVENT_TYPE.guardrails,
      },
      {
        event_type: ALERT_EVENT_TYPE.trace_errors,
        conditions: [
          {
            type: ALERT_CONDITION_TYPE.value_threshold,
            lower_bound: 100,
            upper_bound: 1000,
          },
        ],
      },
      {
        event_type: ALERT_EVENT_TYPE.trace_errors,
        conditions: [
          {
            type: ALERT_CONDITION_TYPE.value_threshold,
            lower_bound: 50,
            upper_bound: 1000,
          },
        ],
      },
    ],
  },
  {
    id: "alert-10",
    name: "Content Safety Violations",
    enabled: true,
    url: "https://api.datadog.com/api/v1/integration/webhooks/configuration/webhooks/abc123",
    created_at: "2024-01-19T17:30:00Z",
    created_by: "jennifer.white",
    last_updated_at: "2024-01-23T13:25:00Z",
    last_updated_by: "michael.garcia",
    events: [
      {
        event_type: ALERT_EVENT_TYPE.guardrails,
        conditions: [
          {
            type: ALERT_CONDITION_TYPE.project_scope,
            value: ["content-moderation"],
          },
        ],
      },
      {
        event_type: ALERT_EVENT_TYPE.guardrails,
        conditions: [
          {
            type: ALERT_CONDITION_TYPE.project_scope,
            value: ["integrations-project"],
          },
        ],
      },
    ],
  },
  {
    id: "alert-11",
    name: "Performance Analytics & Reports",
    enabled: false,
    url: "https://api.mailgun.net/v3/sandbox123.mailgun.org/messages",
    created_at: "2024-01-09T08:45:00Z",
    created_by: "analytics.bot",
    last_updated_at: "2024-01-21T07:30:00Z",
    last_updated_by: "analytics.bot",
    events: [
      {
        event_type: ALERT_EVENT_TYPE.trace_score,
      },
      {
        event_type: ALERT_EVENT_TYPE.prompt_creation,
      },
    ],
  },
];

const getAlertsList = async (
  { signal }: QueryFunctionContext,
  { search, size, page }: UseAlertsListParams,
) => {
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const { data, status } = await api.get<AlertsListResponse>(
    ALERTS_REST_ENDPOINT,
    {
      signal,
      params: {
        ...(search && { name: search }),
        size,
        page,
      },
      validateStatus: (status) => status === 200 || status === 404, // TODO [ANDRII] remove when ready
    },
  );

  // return data; // TODO [ANDRII] uncomment when ready

  // Simulate API delay
  await new Promise((resolve) => setTimeout(resolve, 300));

  // Filter alerts based on search
  let filteredAlerts = MOCK_ALERTS;
  if (search && search.trim()) {
    const searchLower = search.toLowerCase().trim();
    filteredAlerts = MOCK_ALERTS.filter((alert) =>
      alert.name.toLowerCase().includes(searchLower),
    );
  }

  // Calculate pagination
  const startIndex = (page - 1) * size;
  const endIndex = startIndex + size;
  const paginatedAlerts = filteredAlerts.slice(startIndex, endIndex);

  // Mock response structure
  const response: AlertsListResponse = {
    size,
    page,
    content: paginatedAlerts,
    total: filteredAlerts.length,
    sortable_by: ["name", "enabled", "url", "created_at"],
  };

  return response;
};

export default function useAlertsList(
  params: UseAlertsListParams,
  options?: QueryConfig<AlertsListResponse>,
) {
  return useQuery({
    queryKey: [ALERTS_KEY, params],
    queryFn: (context) => getAlertsList(context, params),
    ...options,
  });
}
