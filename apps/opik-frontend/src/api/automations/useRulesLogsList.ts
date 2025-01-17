import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  AUTOMATIONS_KEY,
  AUTOMATIONS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { EVALUATOR_LOG_LEVEL, EvaluatorRuleLogItem } from "@/types/automations";

type UseRulesLogsListParams = {
  projectId: string;
  ruleId: string;
};

export type UseRulesLogsListResponse = {
  content: EvaluatorRuleLogItem[];
};

const getRulesLogsList = async (
  { signal }: QueryFunctionContext,
  { projectId, ruleId }: UseRulesLogsListParams,
) => {
  const { data } = await api.get<UseRulesLogsListResponse>(
    `${AUTOMATIONS_REST_ENDPOINT}projects/${projectId}/evaluators/${ruleId}/logs`,
    {
      signal,
      validateStatus: (status) => status < 500,
    },
  );

  return data && data.content
    ? data
    : {
        content: [
          {
            timestamp: "2025-01-17T11:00:00Z",
            level: EVALUATOR_LOG_LEVEL.INFO,
            message: "Initialization complete.",
          },
          {
            timestamp: "2025-01-17T11:01:00Z",
            level: EVALUATOR_LOG_LEVEL.DEBUG,
            message: "Debugging mode enabled.",
          },
          {
            timestamp: "2025-01-17T11:02:00Z",
            level: EVALUATOR_LOG_LEVEL.WARN,
            message: "Potential issue detected.",
          },
          {
            timestamp: "2025-01-17T11:03:00Z",
            level: EVALUATOR_LOG_LEVEL.ERROR,
            message: "Error encountered during processing.",
          },
          {
            timestamp: "2025-01-17T11:04:00Z",
            level: EVALUATOR_LOG_LEVEL.TRACE,
            message: "Trace log for detailed analysis.",
          },
          {
            timestamp: "2025-01-17T11:05:00Z",
            level: EVALUATOR_LOG_LEVEL.INFO,
            message: "LLM prompt received: 'Generate response with 100 items'.",
          },
          {
            timestamp: "2025-01-17T11:06:00Z",
            level: EVALUATOR_LOG_LEVEL.DEBUG,
            message: "Processing LLM prompt.",
          },
          {
            timestamp: "2025-01-17T11:07:00Z",
            level: EVALUATOR_LOG_LEVEL.INFO,
            message: "LLM prompt processing complete.",
          },
          {
            timestamp: "2025-01-17T11:00:00Z",
            level: EVALUATOR_LOG_LEVEL.INFO,
            message: "Initialization complete.",
          },
          {
            timestamp: "2025-01-17T11:01:00Z",
            level: EVALUATOR_LOG_LEVEL.DEBUG,
            message: "Debugging mode enabled.",
          },
          {
            timestamp: "2025-01-17T11:02:00Z",
            level: EVALUATOR_LOG_LEVEL.WARN,
            message: "Potential issue detected.",
          },
          {
            timestamp: "2025-01-17T11:03:00Z",
            level: EVALUATOR_LOG_LEVEL.ERROR,
            message: "Error encountered during processing.",
          },
          {
            timestamp: "2025-01-17T11:04:00Z",
            level: EVALUATOR_LOG_LEVEL.TRACE,
            message: "Trace log for detailed analysis.",
          },
          {
            timestamp: "2025-01-17T11:05:00Z",
            level: EVALUATOR_LOG_LEVEL.INFO,
            message:
              "LLM prompt received: 'Generate response with 100 items'. The prompt requested a response containing 100 EvaluatorRuleLogItem objects, each with a timestamp, level, and message. The message field should include a variety of log levels such as INFO, ERROR, WARN, TRACE, and DEBUG. The response should be structured as a TypeScript object, with the contents array holding the log items. This prompt is part of a larger evaluation process to test the capabilities of the LLM in generating structured data responses. The LLM is expected to handle the prompt efficiently, ensuring that the generated response adheres to the specified format and includes the required number of log items. The log items should cover a range of scenarios, including initialization, debugging, warnings, errors, and trace logs. This will help in assessing the LLM's ability to generate diverse and contextually relevant log messages. The evaluation process aims to validate the LLM's performance and accuracy in generating structured data responses based on the given prompt.",
          },
          {
            timestamp: "2025-01-17T11:06:00Z",
            level: EVALUATOR_LOG_LEVEL.DEBUG,
            message: "Processing LLM prompt.",
          },
          {
            timestamp: "2025-01-17T11:07:00Z",
            level: EVALUATOR_LOG_LEVEL.INFO,
            message: "LLM prompt processing complete.",
          },
          // ... (92 more items)
          {
            timestamp: "2025-01-17T12:44:00Z",
            level: EVALUATOR_LOG_LEVEL.INFO,
            message: "Final log entry.",
          },
          {
            timestamp: "2025-01-17T11:00:00Z",
            level: "INFO",
            message: "Initialization complete.",
          },
          {
            timestamp: "2025-01-17T11:01:00Z",
            level: "DEBUG",
            message: "Debugging mode enabled.",
          },
          {
            timestamp: "2025-01-17T11:02:00Z",
            level: "WARN",
            message: "Potential issue detected.",
          },
          {
            timestamp: "2025-01-17T11:03:00Z",
            level: "ERROR",
            message: "Error encountered during processing.",
          },
          {
            timestamp: "2025-01-17T11:04:00Z",
            level: "TRACE",
            message: "Trace log for detailed analysis.",
          },
          {
            timestamp: "2025-01-17T11:00:00Z",
            level: "INFO",
            message: "Initialization complete.",
          },
          {
            timestamp: "2025-01-17T11:01:00Z",
            level: "DEBUG",
            message: "Debugging mode enabled.",
          },
          {
            timestamp: "2025-01-17T11:02:00Z",
            level: "WARN",
            message: "Potential issue detected.",
          },
          {
            timestamp: "2025-01-17T11:03:00Z",
            level: "ERROR",
            message: "Error encountered during processing.",
          },
          {
            timestamp: "2025-01-17T11:04:00Z",
            level: "TRACE",
            message: "Trace log for detailed analysis.",
          },
          {
            timestamp: "2025-01-17T11:00:00Z",
            level: "INFO",
            message: "Initialization complete.",
          },
          {
            timestamp: "2025-01-17T11:01:00Z",
            level: "DEBUG",
            message: "Debugging mode enabled.",
          },
          {
            timestamp: "2025-01-17T11:02:00Z",
            level: "WARN",
            message: "Potential issue detected.",
          },
          {
            timestamp: "2025-01-17T11:03:00Z",
            level: "ERROR",
            message: "Error encountered during processing.",
          },
          {
            timestamp: "2025-01-17T11:04:00Z",
            level: "TRACE",
            message: "Trace log for detailed analysis.",
          },
          {
            timestamp: "2025-01-17T11:00:00Z",
            level: "INFO",
            message: "Initialization complete.",
          },
          {
            timestamp: "2025-01-17T11:01:00Z",
            level: "DEBUG",
            message: "Debugging mode enabled.",
          },
          {
            timestamp: "2025-01-17T11:02:00Z",
            level: "WARN",
            message: "Potential issue detected.",
          },
          {
            timestamp: "2025-01-17T11:03:00Z",
            level: "ERROR",
            message: "Error encountered during processing.",
          },
          {
            timestamp: "2025-01-17T11:04:00Z",
            level: "TRACE",
            message: "Trace log for detailed analysis.",
          },
          {
            timestamp: "2025-01-17T11:00:00Z",
            level: "INFO",
            message: "Initialization complete.",
          },
          {
            timestamp: "2025-01-17T11:01:00Z",
            level: "DEBUG",
            message: "Debugging mode enabled.",
          },
          {
            timestamp: "2025-01-17T11:02:00Z",
            level: "WARN",
            message: "Potential issue detected.",
          },
          {
            timestamp: "2025-01-17T11:03:00Z",
            level: "ERROR",
            message: "Error encountered during processing.",
          },
          {
            timestamp: "2025-01-17T11:04:00Z",
            level: "TRACE",
            message: "Trace log for detailed analysis.",
          },
          {
            timestamp: "2025-01-17T11:00:00Z",
            level: "INFO",
            message: "Initialization complete.",
          },
          {
            timestamp: "2025-01-17T11:01:00Z",
            level: "DEBUG",
            message: "Debugging mode enabled.",
          },
          {
            timestamp: "2025-01-17T11:02:00Z",
            level: "WARN",
            message: "Potential issue detected.",
          },
          {
            timestamp: "2025-01-17T11:03:00Z",
            level: "ERROR",
            message: "Error encountered during processing.",
          },
          {
            timestamp: "2025-01-17T11:04:00Z",
            level: "TRACE",
            message: "Trace log for detailed analysis.",
          },
          {
            timestamp: "2025-01-17T11:00:00Z",
            level: "INFO",
            message: "Initialization complete.",
          },
          {
            timestamp: "2025-01-17T11:01:00Z",
            level: "DEBUG",
            message: "Debugging mode enabled.",
          },
          {
            timestamp: "2025-01-17T11:02:00Z",
            level: "WARN",
            message: "Potential issue detected.",
          },
          {
            timestamp: "2025-01-17T11:03:00Z",
            level: "ERROR",
            message: "Error encountered during processing.",
          },
          {
            timestamp: "2025-01-17T11:04:00Z",
            level: "TRACE",
            message: "Trace log for detailed analysis.",
          },
          {
            timestamp: "2025-01-17T11:00:00Z",
            level: "INFO",
            message: "Initialization complete.",
          },
          {
            timestamp: "2025-01-17T11:01:00Z",
            level: "DEBUG",
            message: "Debugging mode enabled.",
          },
          {
            timestamp: "2025-01-17T11:02:00Z",
            level: "WARN",
            message: "Potential issue detected.",
          },
          {
            timestamp: "2025-01-17T11:03:00Z",
            level: "ERROR",
            message: "Error encountered during processing.",
          },
          {
            timestamp: "2025-01-17T11:04:00Z",
            level: "TRACE",
            message: "Trace log for detailed analysis.",
          },
          {
            timestamp: "2025-01-17T11:00:00Z",
            level: "INFO",
            message: "Initialization complete.",
          },
          {
            timestamp: "2025-01-17T11:01:00Z",
            level: "DEBUG",
            message: "Debugging mode enabled.",
          },
          {
            timestamp: "2025-01-17T11:02:00Z",
            level: "WARN",
            message: "Potential issue detected.",
          },
          {
            timestamp: "2025-01-17T11:03:00Z",
            level: "ERROR",
            message: "Error encountered during processing.",
          },
          {
            timestamp: "2025-01-17T11:04:00Z",
            level: "TRACE",
            message: "Trace log for detailed analysis.",
          },
          {
            timestamp: "2025-01-17T11:00:00Z",
            level: "INFO",
            message: "Initialization complete.",
          },
          {
            timestamp: "2025-01-17T11:01:00Z",
            level: "DEBUG",
            message: "Debugging mode enabled.",
          },
          {
            timestamp: "2025-01-17T11:02:00Z",
            level: "WARN",
            message: "Potential issue detected.",
          },
          {
            timestamp: "2025-01-17T11:03:00Z",
            level: "ERROR",
            message: "Error encountered during processing.",
          },
          {
            timestamp: "2025-01-17T11:04:00Z",
            level: "TRACE",
            message: "Trace log for detailed analysis.",
          },
          {
            timestamp: "2025-01-17T11:00:00Z",
            level: "INFO",
            message: "Initialization complete.",
          },
          {
            timestamp: "2025-01-17T11:01:00Z",
            level: "DEBUG",
            message: "Debugging mode enabled.",
          },
          {
            timestamp: "2025-01-17T11:02:00Z",
            level: "WARN",
            message: "Potential issue detected.",
          },
          {
            timestamp: "2025-01-17T11:03:00Z",
            level: "ERROR",
            message: "Error encountered during processing.",
          },
          {
            timestamp: "2025-01-17T11:04:00Z",
            level: "TRACE",
            message: "Trace log for detailed analysis.",
          },
          {
            timestamp: "2025-01-17T11:00:00Z",
            level: "INFO",
            message: "Initialization complete.",
          },
          {
            timestamp: "2025-01-17T11:01:00Z",
            level: "DEBUG",
            message: "Debugging mode enabled.",
          },
          {
            timestamp: "2025-01-17T11:02:00Z",
            level: "WARN",
            message: "Potential issue detected.",
          },
          {
            timestamp: "2025-01-17T11:03:00Z",
            level: "ERROR",
            message: "Error encountered during processing.",
          },
          {
            timestamp: "2025-01-17T11:04:00Z",
            level: "TRACE",
            message: "Trace log for detailed analysis.",
          },
          {
            timestamp: "2025-01-17T11:00:00Z",
            level: "INFO",
            message: "Initialization complete.",
          },
          {
            timestamp: "2025-01-17T11:01:00Z",
            level: "DEBUG",
            message: "Debugging mode enabled.",
          },
          {
            timestamp: "2025-01-17T11:02:00Z",
            level: "WARN",
            message: "Potential issue detected.",
          },
          {
            timestamp: "2025-01-17T11:03:00Z",
            level: "ERROR",
            message: "Error encountered during processing.",
          },
          {
            timestamp: "2025-01-17T11:04:00Z",
            level: "TRACE",
            message: "Trace log for detailed analysis.",
          },
          {
            timestamp: "2025-01-17T11:00:00Z",
            level: "INFO",
            message: "Initialization complete.",
          },
          {
            timestamp: "2025-01-17T11:01:00Z",
            level: "DEBUG",
            message: "Debugging mode enabled.",
          },
          {
            timestamp: "2025-01-17T11:02:00Z",
            level: "WARN",
            message: "Potential issue detected.",
          },
          {
            timestamp: "2025-01-17T11:03:00Z",
            level: "ERROR",
            message: "Error encountered during processing.",
          },
          {
            timestamp: "2025-01-17T11:04:00Z",
            level: "TRACE",
            message: "Trace log for detailed analysis.",
          },
          {
            timestamp: "2025-01-17T12:44:00Z",
            level: "INFO",
            message: "Final log entry.",
          },
        ],
      };
};

export default function useRulesLogsList(
  params: UseRulesLogsListParams,
  options?: QueryConfig<UseRulesLogsListResponse>,
) {
  return useQuery({
    queryKey: [AUTOMATIONS_KEY, params],
    queryFn: (context) => getRulesLogsList(context, params),
    ...options,
  });
}
