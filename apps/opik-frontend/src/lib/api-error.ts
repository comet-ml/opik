import get from "lodash/get";

/**
 * Builds a user-facing error message from a thrown API error.
 *
 * Combines server-provided detail when available: top-level `data.message`
 * comes first; if `data.errors` is also present (validation failures), it is
 * appended below so both layers of detail surface in the toast. Falls back to
 * the axios `error.message` and finally to `fallback`.
 */
export const getApiErrorMessage = (
  error: unknown,
  fallback = "Something went wrong. Please try again.",
): string => {
  const message = get(error, ["response", "data", "message"]);
  const errors = get(error, ["response", "data", "errors"]);

  const joinedErrors = Array.isArray(errors)
    ? errors
        .filter((e): e is string => typeof e === "string" && e.length > 0)
        .join("\n")
    : undefined;

  const serverMessage =
    typeof message === "string" && message.length > 0 ? message : undefined;

  const combined = [serverMessage, joinedErrors].filter(Boolean).join("\n");
  if (combined.length > 0) return combined;

  const axiosMessage = get(error, ["message"]);
  return typeof axiosMessage === "string" && axiosMessage.length > 0
    ? axiosMessage
    : fallback;
};