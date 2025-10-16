import isObject from "lodash/isObject";

/**
 * Checks if the given data is a conversation object (array of messages with role/content structure)
 * @param data - The data to check
 * @returns True if it's a conversation object
 */
export const isConversationData = (data: unknown): boolean => {
  return (
    isObject(data) &&
    Array.isArray(data) &&
    (data as unknown[]).length > 0 &&
    (data as unknown[]).every((msg: unknown) => isObject(msg) && "role" in msg)
  );
};
