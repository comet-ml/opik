import { useQuery } from "@tanstack/react-query";
import { ALERT_EVENT_TYPE } from "@/types/alerts";
import { ALERTS_KEY } from "@/api/api";

export type WebhookExamplesResponse = {
  response_examples: Record<ALERT_EVENT_TYPE, Record<string, unknown>>;
};

// Mock data - replace with actual API call when backend is ready
const MOCK_WEBHOOK_EXAMPLES: WebhookExamplesResponse = {
  response_examples: {
    [ALERT_EVENT_TYPE.trace_errors]: {
      id: "01929d3c-47a1-7849-9b5d-e88c0612bcb6",
      event_type: "trace:errors",
      alert_name: "My Test Alert",
      message:
        "Alerts 'My test Alert': New error detected in trace 'calculate_user_score'",
      project: {
        id: "01929d2c-1020-7d30-b50b-4e335c1a0e33",
        name: "My test project",
      },
      trace: {
        id: "01929d2c-1020-7d30-b50b-4e335c1a0e33",
        name: "calculate_user_score",
        start_time: "2025-10-14T10:30:45.123Z",
        end_time: "2025-10-14T10:30:47.456Z",
        error_info: {
          exception_type: "ValueError",
          message: "Invalid score calculation: negative result",
        },
      },
    },
    [ALERT_EVENT_TYPE.trace_feedback_score]: {
      id: "01929d3c-47a1-7849-9b5d-e88c0612bcb7",
      event_type: "trace:feedback_score",
      alert_name: "My Test Alert",
      message:
        "Alert 'My test Alert': Trace 'evaluate_model' has score 0.42 which is below threshold 0.7",
      project: {
        id: "01929d2c-1020-7d30-b50b-4e335c1a0e33",
        name: "My test project",
      },
      trace: {
        id: "01929d2c-1020-7d30-b50b-4e335c1a0e34",
        name: "evaluate_model",
        start_time: "2025-10-14T10:30:45.123Z",
        end_time: "2025-10-14T10:30:47.456Z",
      },
      feedback_score: {
        name: "hallucination",
        value: 0.42,
        threshold: 0.7,
      },
    },
    [ALERT_EVENT_TYPE.trace_thread_feedback_score]: {
      id: "01929d3c-47a1-7849-9b5d-e88c0612bcb8",
      event_type: "trace_thread:feedback_score",
      alert_name: "My Test Alert",
      message:
        "Alert 'My test Alert': Thread score 0.35 is below threshold 0.5",
      project: {
        id: "01929d2c-1020-7d30-b50b-4e335c1a0e33",
        name: "My test project",
      },
      thread: {
        id: "01929d2c-1020-7d30-b50b-4e335c1a0e35",
        aggregation_type: "My test Alert",
      },
      feedback_score: {
        name: "conversation_quality",
        value: 0.35,
        threshold: 0.5,
      },
    },
    [ALERT_EVENT_TYPE.prompt_created]: {
      id: "01929d3c-47a1-7849-9b5d-e88c0612bcb9",
      event_type: "prompt:created",
      alert_name: "My Test Alert",
      message:
        "Alert 'My test Alert': New prompt 'customer_support_template' created in workspace 'production'",
      workspace: {
        id: "01929d2c-1020-7d30-b50b-4e335c1a0e36",
        name: "production",
      },
      prompt: {
        id: "01929d2c-1020-7d30-b50b-4e335c1a0e37",
        name: "customer_support_template",
        created_at: "2025-10-14T10:30:45.123Z",
        created_by: "user@example.com",
      },
    },
    [ALERT_EVENT_TYPE.prompt_committed]: {
      id: "01929d3c-47a1-7849-9b5d-e88c0612bcba",
      event_type: "prompt:committed",
      alert_name: "My Test Alert",
      message:
        "Alert 'My test Alert': Prompt 'customer_support_template' (version 3) committed in workspace 'production'",
      workspace: {
        id: "01929d2c-1020-7d30-b50b-4e335c1a0e36",
        name: "production",
      },
      prompt: {
        id: "01929d2c-1020-7d30-b50b-4e335c1a0e37",
        name: "customer_support_template",
        version: 3,
        commit_message: "Updated greeting message",
        committed_at: "2025-10-14T10:30:45.123Z",
        committed_by: "user@example.com",
      },
    },
    [ALERT_EVENT_TYPE.prompt_deleted]: {
      id: "01929d3c-47a1-7849-9b5d-e88c0612bcbb",
      event_type: "prompt:deleted",
      alert_name: "My Test Alert",
      message:
        "Alert 'My test Alert': Prompt 'old_template' deleted from workspace 'production'",
      workspace: {
        id: "01929d2c-1020-7d30-b50b-4e335c1a0e36",
        name: "production",
      },
      prompt: {
        id: "01929d2c-1020-7d30-b50b-4e335c1a0e38",
        name: "old_template",
        deleted_at: "2025-10-14T10:30:45.123Z",
        deleted_by: "user@example.com",
      },
    },
    [ALERT_EVENT_TYPE.trace_guardrails_triggered]: {
      id: "01929d3c-47a1-7849-9b5d-e88c0612bcbc",
      event_type: "trace:guardrails_triggered",
      alert_name: "My Test Alert",
      message:
        "Alert 'My test Alert': Guardrail 'content_safety' triggered in trace 'process_user_input'",
      project: {
        id: "01929d2c-1020-7d30-b50b-4e335c1a0e33",
        name: "My test project",
      },
      trace: {
        id: "01929d2c-1020-7d30-b50b-4e335c1a0e39",
        name: "process_user_input",
        start_time: "2025-10-14T10:30:45.123Z",
        end_time: "2025-10-14T10:30:47.456Z",
      },
      guardrail: {
        name: "content_safety",
        rule: "PII_detection",
        triggered_at: "2025-10-14T10:30:46.789Z",
        details: {
          detected_entities: ["email", "phone_number"],
          confidence: 0.95,
        },
      },
    },
  },
};

const getWebhookExamples = async (): Promise<WebhookExamplesResponse> => {
  // TODO: Replace with actual API call when backend is ready
  // const { data } = await api.get<WebhookExamplesResponse>(
  //   `${ALERTS_REST_ENDPOINT}webhooks/examples`
  // );
  // return data;

  // Mock implementation
  return new Promise((resolve) => {
    setTimeout(() => resolve(MOCK_WEBHOOK_EXAMPLES), 100);
  });
};

export default function useWebhookExamplesQuery() {
  return useQuery({
    queryKey: [ALERTS_KEY, "webhook-examples"],
    queryFn: getWebhookExamples,
  });
}
