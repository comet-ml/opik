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
import { SPAN_TYPE, Thread, TRACE_VISIBILITY_MODE } from "@/types/traces";
import { safelyParseJSON } from "@/lib/utils";
import isEmpty from "lodash/isEmpty";
import { Filter } from "@/types/filters";
import { createFilter } from "./filters";
import { SPAN_TYPE_FILTER_COLUMN } from "@/components/pages-shared/traces/TraceDetailsPanel/TraceTreeViewer/helpers";

const MESSAGES_DIVIDER = `\n\n  ----------------- \n\n`;

export const generateTagVariant = (label: string) => {
  const hash = md5(label);
  const index = parseInt(hash.slice(-8), 16);
  return TAG_VARIANTS[index % TAG_VARIANTS.length];
};

export const isObjectSpan = (object: object) =>
  Boolean(get(object, "trace_id", false));

export const isObjectThread = (object: object): object is Thread =>
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

type PrettifyMessageConfig = {
  type: "input" | "output";
};

type PrettifyMessageResponse = {
  message: object | string | undefined;
  prettified: boolean;
};

/**
 * Extracts the last human/user message content from an array of messages.
 * Supports both string content and array content (e.g., multimodal messages).
 * Used by LangGraph and LangChain prettify logic.
 */
const extractLastHumanMessageContent = (
  messages: unknown[],
): string | undefined => {
  const humanMessageContents: string[] = [];

  for (const m of messages) {
    if (isObject(m) && "type" in m && m.type === "human" && "content" in m) {
      // Content can be a string
      if (isString(m.content) && m.content !== "") {
        humanMessageContents.push(m.content);
      }
      // Or content can be an array with text content (e.g., multimodal messages)
      else if (isArray(m.content)) {
        const lastTextContent = findLast(
          m.content,
          (c) =>
            isObject(c) &&
            "type" in c &&
            c.type === "text" &&
            "text" in c &&
            isString(c.text) &&
            c.text !== "",
        );

        if (lastTextContent && "text" in lastTextContent) {
          humanMessageContents.push(lastTextContent.text);
        }
      }
    }
  }

  return humanMessageContents.length > 0
    ? last(humanMessageContents)
    : undefined;
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
    // Filter for user messages only, then get the last one
    const userMessages = message.messages.filter(
      (m) => isObject(m) && "role" in m && m.role === "user" && "content" in m,
    );
    const lastMessage = last(userMessages);
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

const prettifyOpenAIAgentsMessageLogic = (
  message: object | string | undefined,
  config: PrettifyMessageConfig,
): string | undefined => {
  if (
    config.type === "input" &&
    isObject(message) &&
    "input" in message &&
    isArray(message.input)
  ) {
    const userMessages = message.input.filter(
      (m) =>
        isObject(m) &&
        "role" in m &&
        m.role === "user" &&
        "content" in m &&
        isString(m.content) &&
        m.content !== "",
    );

    if (userMessages.length > 0) {
      return userMessages.map((m) => m.content).join(MESSAGES_DIVIDER);
    }
  } else if (
    config.type === "output" &&
    isObject(message) &&
    "output" in message &&
    isArray(message.output)
  ) {
    const assistantMessageObjects = message.output.filter(
      (m) =>
        isObject(m) &&
        "role" in m &&
        m.role === "assistant" &&
        "type" in m &&
        m.type === "message" &&
        "content" in m &&
        isArray(m.content),
    );

    const userMessages = assistantMessageObjects.reduce<string[]>((acc, m) => {
      return acc.concat(
        m.content
          .filter(
            (c: unknown) =>
              isObject(c) &&
              "type" in c &&
              c.type === "output_text" &&
              "text" in c &&
              isString(c.text) &&
              c.text !== "",
          )
          .map((c: { text: string }) => c.text),
      );
    }, []);

    if (userMessages.length > 0) {
      return userMessages.join(MESSAGES_DIVIDER);
    }
  }

  return undefined;
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
    return extractLastHumanMessageContent(message.messages);
  } else if (
    config.type === "output" &&
    isObject(message) &&
    "messages" in message &&
    isArray(message.messages)
  ) {
    // Get the last AI message, and extract the string output from the various supported formats
    const aiMessages = [];

    // Iterate on all AI messages
    for (const m of message.messages) {
      if (isObject(m) && "type" in m && m.type === "ai" && "content" in m) {
        // The message can either contains a string attribute named `content`
        if (isString(m.content)) {
          aiMessages.push(m.content);
        }
        // Or content can be an array with text content. For example when using OpenAI chat model with the Responses API
        // https://python.langchain.com/docs/integrations/chat/openai/#responses-api
        else if (isArray(m.content)) {
          const textItems = m.content.filter(
            (c) =>
              isObject(c) &&
              "type" in c &&
              c.type === "text" &&
              "text" in c &&
              isString(c.text) &&
              c.text !== "",
          );

          // Check that there is only one text item
          if (textItems.length === 1) {
            aiMessages.push(textItems[0].text);
          }
        }
      }
    }

    if (aiMessages.length > 0) {
      return last(aiMessages);
    }
  }
};

const prettifyLangChainLogic = (
  message: object | string | undefined,
  config: PrettifyMessageConfig,
): string | undefined => {
  // Some older models can return multiple generations, and Langchain can be
  // called with several prompts at the same time. When that happens, there is
  // no clear way to "know" which generation or prompt the user wants to see.
  // Given that it's not the common case, we should only prettify when there
  // is a single prompt and generation.
  if (
    config.type === "input" &&
    isObject(message) &&
    "messages" in message &&
    isArray(message.messages) &&
    message.messages.length == 1 &&
    isArray(message.messages[0])
  ) {
    return extractLastHumanMessageContent(message.messages[0]);
  } else if (
    config.type === "output" &&
    isObject(message) &&
    "generations" in message &&
    isArray(message.generations) &&
    message.generations.length == 1 &&
    isArray(message.generations[0])
  ) {
    // Get the last AI message
    const aiMessages = message.generations[0].filter(
      (m) =>
        isObject(m) &&
        "message" in m &&
        isObject(m.message) &&
        "kwargs" in m.message &&
        isObject(m.message.kwargs) &&
        "type" in m.message.kwargs &&
        m.message.kwargs.type === "ai" &&
        "text" in m &&
        isString(m.text) &&
        m.text !== "",
    );

    if (aiMessages.length > 0) {
      return last(aiMessages).text;
    }
  }
};

/**
 * Prettifies Demo project's blocks-based message format.
 *
 * Handles two formats:
 * - Direct: { blocks: [{ block_type: "text", text: "..." }] }
 * - Nested: { output: { blocks: [{ block_type: "text", text: "..." }] } }
 */
const prettifyDemoProjectLogic = (
  message: object | string | undefined,
  config: PrettifyMessageConfig,
): string | undefined => {
  const extractTextFromBlocks = (blocks: unknown[]): string | undefined => {
    const textBlocks = blocks.filter(
      (block): block is { block_type: string; text: string } =>
        isObject(block) &&
        "block_type" in block &&
        block.block_type === "text" &&
        "text" in block &&
        isString(block.text) &&
        block.text.trim() !== "",
    );

    return textBlocks.length > 0
      ? textBlocks.map((block) => block.text).join("\n\n")
      : undefined;
  };

  // Handle direct blocks structure: { blocks: [...] }
  if (isObject(message) && "blocks" in message && isArray(message.blocks)) {
    return extractTextFromBlocks(message.blocks);
  }

  // Handle nested blocks structure: { output: { blocks: [...] } }
  if (
    config.type === "output" &&
    isObject(message) &&
    "output" in message &&
    isObject(message.output) &&
    "blocks" in message.output &&
    isArray(message.output.blocks)
  ) {
    return extractTextFromBlocks(message.output.blocks);
  }

  return undefined;
};

const prettifyCustomMessagingLogic = (
  message: object | string | undefined,
  config: PrettifyMessageConfig,
): string | undefined => {
  if (!isObject(message)) return undefined;

  if (config.type === "input") {
    if ("prompt" in message && isArray(message.prompt)) {
      const userMessages = message.prompt.filter(
        (m) =>
          isObject(m) &&
          "role" in m &&
          m.role === "user" &&
          "content" in m &&
          isString(m.content) &&
          m.content !== "",
      );

      if (userMessages.length > 0) {
        return last(userMessages).content;
      }
    }
  } else if (config.type === "output") {
    if ("candidates" in message && isArray(message.candidates)) {
      const lastCandidate = last(message.candidates);
      if (
        lastCandidate &&
        isObject(lastCandidate) &&
        "content" in lastCandidate &&
        isObject(lastCandidate.content) &&
        "parts" in lastCandidate.content &&
        isArray(lastCandidate.content.parts)
      ) {
        const lastTextPart = findLast(
          lastCandidate.content.parts,
          (part) =>
            isObject(part) &&
            "text" in part &&
            isString(part.text) &&
            part.text !== "",
        );

        if (lastTextPart && "text" in lastTextPart) {
          return lastTextPart.text;
        }
      }
    }

    if ("output" in message && isArray(message.output)) {
      const lastAiMessage = findLast(
        message.output,
        (m) =>
          isObject(m) &&
          "type" in m &&
          m.type === "ai" &&
          "content" in m &&
          isString(m.content) &&
          m.content !== "",
      );

      if (lastAiMessage && "content" in lastAiMessage) {
        return lastAiMessage.content;
      }
    }
  }

  return undefined;
};

const prettifyGenericLogic = (
  message: object | string | undefined,
  config: PrettifyMessageConfig,
): string | undefined => {
  const PREDEFINED_KEYS_MAP = {
    input: [
      "question",
      "message",
      "messages",
      "user_input",
      "user_text",
      "query",
      "input_prompt",
      "prompt",
      "sys.query", // Dify
      "contents",
      "user_payload",
      "user_query",
      "input",
      "text",
      // some customer specific formats
      "query.body.question",
      "content",
    ],
    output: [
      "answer",
      "output",
      "response",
      "reply",
      "final_output",
      // some customer specific formats
      "answer.answer",
    ],
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
      prettified: true,
    } as PrettifyMessageResponse;
  }
  try {
    let processedMessage = prettifyOpenAIMessageLogic(message, config);

    if (!isString(processedMessage)) {
      processedMessage = prettifyOpenAIAgentsMessageLogic(message, config);
    }

    if (!isString(processedMessage)) {
      processedMessage = prettifyADKMessageLogic(message, config);
    }

    if (!isString(processedMessage)) {
      processedMessage = prettifyLangGraphLogic(message, config);
    }

    if (!isString(processedMessage)) {
      processedMessage = prettifyLangChainLogic(message, config);
    }

    if (!isString(processedMessage)) {
      processedMessage = prettifyDemoProjectLogic(message, config);
    }

    if (!isString(processedMessage)) {
      processedMessage = prettifyCustomMessagingLogic(message, config);
    }

    if (!isString(processedMessage)) {
      processedMessage = prettifyGenericLogic(message, config);
    }

    // attempt to improve JSON string if the message is serialised JSON string
    if (isString(processedMessage)) {
      const json = safelyParseJSON(processedMessage, true);

      if (!isEmpty(json)) {
        processedMessage = JSON.stringify(json, null, 2);
      }
    }

    return {
      message: processedMessage ? processedMessage : message,
      prettified: Boolean(processedMessage),
    } as PrettifyMessageResponse;
  } catch (error) {
    return {
      message,
      prettified: false,
    } as PrettifyMessageResponse;
  }
};

/**
 * Predicate to check if a filter is a tool span filter.
 */
const isToolFilter = (filter: Filter): boolean => {
  return (
    filter.field === SPAN_TYPE_FILTER_COLUMN.id &&
    filter.value === SPAN_TYPE.tool
  );
};

export const manageToolFilter = (
  currentFilters: Filter[] | null | undefined,
  shouldFilter: boolean,
): Filter[] => {
  const filters = currentFilters || [];
  const hasToolFilter = filters.some(isToolFilter);

  if (shouldFilter && !hasToolFilter) {
    return [
      ...filters,
      createFilter({
        id: SPAN_TYPE_FILTER_COLUMN.id,
        field: SPAN_TYPE_FILTER_COLUMN.id,
        type: SPAN_TYPE_FILTER_COLUMN.type,
        operator: "=",
        value: SPAN_TYPE.tool,
      }),
    ];
  }

  if (!shouldFilter && hasToolFilter) {
    return filters.filter((filter) => !isToolFilter(filter));
  }

  return filters;
};
