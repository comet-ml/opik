import md5 from "md5";
import get from "lodash/get";
import isNumber from "lodash/isNumber";
import { TAG_VARIANTS } from "@/components/ui/tag";
import { ExperimentItem } from "@/types/datasets";
import { TRACE_VISIBILITY_MODE } from "@/types/traces";

export const generateTagVariant = (label: string) => {
  const hash = md5(label);
  const index = parseInt(hash.slice(-8), 16);
  return TAG_VARIANTS[index % TAG_VARIANTS.length];
};

export const isObjectSpan = (object: object): object is Span => 
  Boolean(get(object, "trace_id", false));

export const isObjectThread = (object: object): object is Thread
  Boolean(get(object, "thread_model_id", false)) ||
  Boolean(get(object, "first_message", false)) ||
  Boolean(get(object, "last_message", false));

export const isNumericFeedbackScoreValid = (
  { min, max }: { min: number; max: number },
  value?: number | "",
) => isNumber(value) && value >= min && value <= max;

export const traceExist = (item: ExperimentItem) =>
  item.output || item.input || item.feedback_scores;

export const traceVisible = (item: ExperimentItem) =>
  item.trace_visibility_mode === TRACE_VISIBILITY_MODE.default;
