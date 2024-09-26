import md5 from "md5";
import get from "lodash/get";
import isNumber from "lodash/isNumber";
import { TAG_VARIANTS } from "@/components/ui/tag";
import { ExperimentItem } from "@/types/datasets";

export const generateTagVariant = (label: string) => {
  const hash = md5(label);
  const index = parseInt(hash.slice(-8), 16);
  return TAG_VARIANTS[index % TAG_VARIANTS.length];
};

export const isObjectSpan = (object: object) => get(object, "trace_id", false);

export const isNumericFeedbackScoreValid = (
  { min, max }: { min: number; max: number },
  value?: number | "",
) => isNumber(value) && value >= min && value <= max;

export const traceExist = (item: ExperimentItem) =>
  item.output || item.input || item.feedback_scores;
