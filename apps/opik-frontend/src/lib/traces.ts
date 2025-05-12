import md5 from "md5";
import get from "lodash/get";
import last from "lodash/last";
import findLast from "lodash/findLast";
import isArray from "lodash/isArray";
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
  if (
    config.type === "input" &&
    isObject(message) &&
    "messages" in message &&
    isArray(message.messages)
  ) {
    const lastMessage = last(message.messages);
    if (lastMessage && "content" in lastMessage) {
      if (isString(lastMessage.content) && lastMessage.content.length > 0) {
        return {
          message: lastMessage.content,
          prettified: true,
        } as PrettifyMessageResponse;
      } else if (isArray(lastMessage.content)) {
        const lastTextContent = findLast(
          lastMessage.content,
          (c) => c.type === "text",
        );

        if (
          lastTextContent &&
          "text" in lastTextContent &&
          isString(lastTextContent.text) &&
          lastTextContent.text.length > 0
        ) {
          return {
            message: lastTextContent.text,
            prettified: true,
          };
        }
      }
    }
  } else if (
    config.type === "output" &&
    isObject(message) &&
    "choices" in message &&
    isArray(message.choices)
  ) {
    const lastChoice = last(message.choices);
    if (
      lastChoice &&
      "message" in lastChoice &&
      isObject(lastChoice.message) &&
      "content" in lastChoice.message &&
      isString(lastChoice.message.content) &&
      lastChoice.message.content.length > 0
    ) {
      return {
        message: lastChoice.message.content,
        prettified: true,
      } as PrettifyMessageResponse;
    }
  }

  const PREDEFINED_KEYS_MAP = {
    input: ["question", "messages", "user_input", "query", "input_prompt"],
    output: ["answer", "output", "response"],
  };

  let unwrappedMessage = message;

  if (isObject(message) && Object.keys(message).length === 1) {
    unwrappedMessage = get(message, Object.keys(message)[0]);
  }

  if (isString(unwrappedMessage)) {
    return {
      message: unwrappedMessage,
      prettified: message !== unwrappedMessage,
    } as PrettifyMessageResponse;
  }

  if (isObject(unwrappedMessage)) {
    if (Object.keys(unwrappedMessage).length === 1) {
      const value = get(message, Object.keys(unwrappedMessage)[0]);

      if (isString(value)) {
        return {
          message: value,
          prettified: true,
        } as PrettifyMessageResponse;
      }
    } else {
      for (const key of PREDEFINED_KEYS_MAP[config.type]) {
        const value = get(unwrappedMessage, key);
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
