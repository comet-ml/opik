import { AxiosError } from "axios";
import get from "lodash/get";

export type TagUpdateFields = {
  tagsToAdd?: string[];
  tagsToRemove?: string[];
};

export const buildTagUpdatePayload = <T extends TagUpdateFields>(
  update: T,
): Record<string, unknown> => {
  const { tagsToAdd, tagsToRemove, ...rest } = update;
  const payload: Record<string, unknown> = { ...rest };
  if (tagsToAdd !== undefined) payload.tags_to_add = tagsToAdd;
  if (tagsToRemove !== undefined) payload.tags_to_remove = tagsToRemove;
  return payload;
};

export const extractErrorMessage = (error: AxiosError): string => {
  const errors = get(error, ["response", "data", "errors"]);
  return (
    (Array.isArray(errors) && errors.length > 0
      ? errors.join(", ")
      : undefined) ??
    get(error, ["response", "data", "message"]) ??
    error.message ??
    "An unknown error occurred. Please try again later."
  );
};
