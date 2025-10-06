/* eslint-disable @typescript-eslint/no-explicit-any */
import { Trace, Span, SPAN_TYPE } from "@/types/traces";
import isString from "lodash/isString";
import isObject from "lodash/isObject";
import isNumber from "lodash/isNumber";
import isArray from "lodash/isArray";

/**
 * Type guard to check if data is a Trace
 */
export const isTrace = (data: unknown): data is Trace => {
  if (!isObject(data)) return false;

  // Required Trace fields
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const hasRequiredFields =
    isString((data as any).id) &&
    isString((data as any).name) &&
    (data as any).input !== undefined &&
    (data as any).output !== undefined &&
    isString((data as any).start_time) &&
    isString((data as any).end_time) &&
    isNumber((data as any).duration) &&
    isString((data as any).created_at) &&
    isString((data as any).last_updated_at) &&
    isObject((data as any).metadata) &&
    isString((data as any).project_id);

  if (!hasRequiredFields) return false;

  // Optional Trace fields (if present, must be correct type)
  if (
    (data as any).feedback_scores !== undefined &&
    !isArray((data as any).feedback_scores)
  )
    return false;
  if ((data as any).comments !== undefined && !isArray((data as any).comments))
    return false;
  if ((data as any).tags !== undefined && !isArray((data as any).tags))
    return false;
  if ((data as any).usage !== undefined && !isObject((data as any).usage))
    return false;
  if (
    (data as any).total_estimated_cost !== undefined &&
    !isNumber((data as any).total_estimated_cost)
  )
    return false;
  if (
    (data as any).error_info !== undefined &&
    !isObject((data as any).error_info)
  )
    return false;
  if (
    (data as any).guardrails_validations !== undefined &&
    !isArray((data as any).guardrails_validations)
  )
    return false;
  if (
    (data as any).span_count !== undefined &&
    !isNumber((data as any).span_count)
  )
    return false;
  if (
    (data as any).llm_span_count !== undefined &&
    !isNumber((data as any).llm_span_count)
  )
    return false;
  if (
    (data as any).thread_id !== undefined &&
    !isString((data as any).thread_id)
  )
    return false;
  if (
    (data as any).workspace_name !== undefined &&
    !isString((data as any).workspace_name)
  )
    return false;

  return true;
};

/**
 * Type guard to check if data is a Span
 */
export const isSpan = (data: unknown): data is Span => {
  if (!isObject(data)) return false;

  // Required Span fields
  const hasRequiredFields =
    isString((data as any).id) &&
    isString((data as any).name) &&
    (data as any).input !== undefined &&
    (data as any).output !== undefined &&
    isString((data as any).start_time) &&
    isString((data as any).end_time) &&
    isNumber((data as any).duration) &&
    isString((data as any).created_at) &&
    isString((data as any).last_updated_at) &&
    isObject((data as any).metadata) &&
    isString((data as any).parent_span_id) &&
    isString((data as any).trace_id) &&
    isString((data as any).project_id) &&
    isString((data as any).type) &&
    Object.values(SPAN_TYPE).includes((data as any).type as SPAN_TYPE);

  if (!hasRequiredFields) return false;

  // Optional Span fields (if present, must be correct type)
  if (
    (data as any).feedback_scores !== undefined &&
    !isArray((data as any).feedback_scores)
  )
    return false;
  if ((data as any).comments !== undefined && !isArray((data as any).comments))
    return false;
  if ((data as any).tags !== undefined && !isArray((data as any).tags))
    return false;
  if ((data as any).usage !== undefined && !isObject((data as any).usage))
    return false;
  if (
    (data as any).total_estimated_cost !== undefined &&
    !isNumber((data as any).total_estimated_cost)
  )
    return false;
  if (
    (data as any).error_info !== undefined &&
    !isObject((data as any).error_info)
  )
    return false;
  if (
    (data as any).guardrails_validations !== undefined &&
    !isArray((data as any).guardrails_validations)
  )
    return false;
  if ((data as any).model !== undefined && !isString((data as any).model))
    return false;
  if ((data as any).provider !== undefined && !isString((data as any).provider))
    return false;
  if (
    (data as any).workspace_name !== undefined &&
    !isString((data as any).workspace_name)
  )
    return false;

  return true;
};

/**
 * Type guard to check if data is either a Trace or Span
 */
export const isTraceOrSpan = (data: unknown): data is Trace | Span => {
  return isTrace(data) || isSpan(data);
};

/**
 * Type guard to check if data has the basic structure of trace/span data
 * (less strict than isTraceOrSpan, useful for partial data)
 */
export const hasTraceSpanStructure = (data: unknown): boolean => {
  if (!isObject(data)) return false;

  // Check for basic required fields that both Trace and Span have
  return (
    isString((data as any).id) &&
    isString((data as any).name) &&
    isObject((data as any).input) &&
    isObject((data as any).output) &&
    isString((data as any).start_time) &&
    isString((data as any).end_time) &&
    isNumber((data as any).duration) &&
    isString((data as any).created_at) &&
    isString((data as any).last_updated_at) &&
    isObject((data as any).metadata) &&
    isString((data as any).project_id)
  );
};
