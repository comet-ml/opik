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

const prettifyOpenAIMessageLogic = (
  message: object | string | undefined,
  config: PrettifyMessageConfig,
): string | undefined => {
  if (
    config.type === "input" &&
    isObject(message) &&
    "messages" in message &&
    isArray(message.messages)
  ) {
    const lastMessage = last(message.messages);
    if (lastMessage && isObject(lastMessage) && "content" in lastMessage) {
      if (isString(lastMessage.content) && lastMessage.content.length > 0) {
        return lastMessage.content;
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
          return lastTextContent.text;
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
      return lastChoice.message.content;
    }
  }
};

const prettifyADKMessageLogic = (
  message: object | string | undefined,
  config: PrettifyMessageConfig,
): string | undefined => {
  if (config.type === "input" && isObject(message)) {
    const unwrappedMessage =
      !("parts" in message) &&
      "contents" in message &&
      isArray(message.contents)
        ? last(message.contents)
        : message;

    if (
      isObject(unwrappedMessage) &&
      "parts" in unwrappedMessage &&
      isArray(unwrappedMessage.parts)
    ) {
      const lastPart = last(unwrappedMessage.parts);
      if (isObject(lastPart) && "text" in lastPart && isString(lastPart.text)) {
        return lastPart.text;
      }
    }
  } else if (
    config.type === "output" &&
    isObject(message) &&
    "content" in message &&
    isObject(message.content) &&
    "parts" in message.content &&
    isArray(message.content.parts)
  ) {
    const lastPart = last(message.content.parts);
    if (isObject(lastPart) && "text" in lastPart && isString(lastPart.text)) {
      return lastPart.text;
    }
  }
};

const prettifyLangGraphLogic = (
  message: object | string | undefined,
  config: PrettifyMessageConfig,
): string | undefined => {
  if (
    config.type === "input" &&
    isObject(message) &&
    "messages" in message &&
    isArray(message.messages)
  ) {
    const lastMessage = last(message.messages);
    if (
      lastMessage &&
      isArray(lastMessage) &&
      lastMessage.length === 2 &&
      isString(lastMessage[1])
    ) {
      return lastMessage[1];
    }
  } else if (
    config.type === "output" &&
    isObject(message) &&
    "messages" in message &&
    isArray(message.messages) &&
    message.messages.every((m) => isObject(m))
  ) {
    const divider = `\n\n  ----------------- \n\n`;

    const humanMessages = message.messages.filter(
      (m) =>
        "type" in m &&
        m.type === "human" &&
        "content" in m &&
        isString(m.content) &&
        m.content !== "",
    );

    if (humanMessages.length > 0) {
      return humanMessages.map((m) => m.content).join(divider);
    }
  }
};

const prettifyGenericLogic = (
  message: object | string | undefined,
  config: PrettifyMessageConfig,
): string | undefined => {
  const PREDEFINED_KEYS_MAP = {
    input: ["question", "messages", "user_input", "query", "input_prompt"],
    output: ["answer", "output", "response"],
  };

  let unwrappedMessage = message;

  if (isObject(message) && Object.keys(message).length === 1) {
    unwrappedMessage = get(message, Object.keys(message)[0]);
  }

  if (isString(unwrappedMessage)) {
    return unwrappedMessage;
  }

  if (isObject(unwrappedMessage)) {
    if (Object.keys(unwrappedMessage).length === 1) {
      const value = get(unwrappedMessage, Object.keys(unwrappedMessage)[0]);

      if (isString(value)) {
        return value;
      }
    } else {
      for (const key of PREDEFINED_KEYS_MAP[config.type]) {
        const value = get(unwrappedMessage, key);
        if (isString(value)) {
          return value;
        }
      }
    }
  }
};

export const prettifyMessage = (
  message: object | string | undefined,
  config: PrettifyMessageConfig = {
    type: "input",
  },
) => {
  if (isString(message)) {
    return {
      message,
      prettified: false,
    } as PrettifyMessageResponse;
  }

  let processedMessage = prettifyOpenAIMessageLogic(message, config);

  if (!isString(processedMessage)) {
    processedMessage = prettifyADKMessageLogic(message, config);
  }

  if (!isString(processedMessage)) {
    processedMessage = prettifyLangGraphLogic(message, config);
  }

  if (!isString(processedMessage)) {
    processedMessage = prettifyGenericLogic(message, config);
  }

  return {
    message: processedMessage ? processedMessage : message,
    prettified: Boolean(processedMessage),
  } as PrettifyMessageResponse;
};
