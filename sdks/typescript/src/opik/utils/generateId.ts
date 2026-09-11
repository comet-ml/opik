import { v7 as uuid, validate, version } from "uuid";

export const generateId = () => uuid();

/**
 * Returns `true` only when `value` parses as a valid UUID and its version is 7.
 */
export const isValidUuidV7 = (value: unknown): boolean => {
  if (typeof value !== "string" || !validate(value)) {
    return false;
  }
  return version(value) === 7;
};