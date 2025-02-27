import md5 from "md5";
import get from "lodash/get";
import isNumber from "lodash/isNumber";
import isObject from "lodash/isObject";
import isString from "lodash/isString";
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

type PrettifyMessageConfig = {
  type: "input" | "output";
};

type PrettifyMessageResponse = {
  message: object | string | undefined;
  prettified: boolean;
};

export const prettifyMessage = (
  message: object | string | undefined,
  config: PrettifyMessageConfig = {
    type: "input",
  },
) => {
  const PREDEFINED_KEYS_MAP = {
    input: ["question, messages, user_input, query, input_prompt"],
    output: ["answer", "output", "response"],
  };

  if (isObject(message)) {
    if (Object.keys(message).length === 1) {
      const value = get(message, Object.keys(message)[0]);

      if (isObject(value) || isString(value)) {
        return {
          message: value,
          prettified: true,
        } as PrettifyMessageResponse;
      }
    } else {
      for (const key of PREDEFINED_KEYS_MAP[config.type]) {
        const value = get(message, key);
        if (isString(value)) {
          return {
            message: value,
            prettified: true,
          } as PrettifyMessageResponse;
        }
      }
    }
  }

  return {
    message,
    prettified: false,
  } as PrettifyMessageResponse;
};
