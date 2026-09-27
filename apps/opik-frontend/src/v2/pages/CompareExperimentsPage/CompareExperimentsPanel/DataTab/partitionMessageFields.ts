import { DatasetItem } from "@/types/datasets";
import { mapAndCombineMessages } from "@/shared/PrettyLLMMessage/llmMessages";

export type PartitionedMessageFields = {
  messageData: DatasetItem["data"];
  remainingData: DatasetItem["data"];
};

/**
 * Splits selected dataset columns into the ones that render as a conversation
 * and the ones that do not.
 *
 * Detection runs per key rather than over the whole object: a selection can mix
 * a conversation with plain scalar columns, and passing the whole object to the
 * messages viewer would render only the conversation and silently drop the rest.
 */
export const partitionMessageFields = (
  data: DatasetItem["data"] | undefined,
): PartitionedMessageFields => {
  const messageData: Record<string, unknown> = {};
  const remainingData: Record<string, unknown> = {};

  Object.entries(data ?? {}).forEach(([key, value]) => {
    const isMessages =
      mapAndCombineMessages({ [key]: value }, undefined).messages.length > 0;

    if (isMessages) {
      messageData[key] = value;
    } else {
      remainingData[key] = value;
    }
  });

  return { messageData, remainingData };
};
