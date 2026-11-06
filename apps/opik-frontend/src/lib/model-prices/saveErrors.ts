import type { ModelPriceProblem, ModelPriceProblems } from "./types";

export type ModelPricesSaveError =
  | { kind: "forbidden" }
  | { kind: "conflict" }
  | { kind: "invalid"; problems: ModelPriceProblems }
  | { kind: "disabled" }
  | { kind: "unknown"; message?: string };

type JsonObject = Record<string, unknown>;

const isObject = (value: unknown): value is JsonObject =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const isProblem = (value: unknown): value is ModelPriceProblem =>
  isObject(value) &&
  typeof value.code === "string" &&
  typeof value.message === "string";

const problemsOf = (body: unknown): ModelPriceProblems | undefined => {
  if (!isObject(body) || !Array.isArray(body.errors)) {
    return undefined;
  }
  const errors = body.errors.filter(isProblem);
  const warnings = Array.isArray(body.warnings)
    ? body.warnings.filter(isProblem)
    : [];
  return errors.length ? { errors, warnings } : undefined;
};

const messageOf = (body: unknown): string | undefined => {
  if (!isObject(body)) {
    return undefined;
  }
  if (typeof body.message === "string") return body.message;
  if (typeof body.msg === "string") return body.msg;
  if (Array.isArray(body.errors) && typeof body.errors[0] === "string") {
    return body.errors[0];
  }
  return undefined;
};

const MAX_MESSAGE_LENGTH = 200;

const isControl = (char: string) => {
  const code = char.charCodeAt(0);
  return code < 32 || (code >= 127 && code < 160);
};

const cleanMessage = (message: string | undefined) => {
  if (message === undefined) return undefined;
  const printable = Array.from(message, (char) =>
    isControl(char) ? " " : char,
  ).join("");
  const collapsed = printable.replace(/\s+/g, " ").trim();
  if (!collapsed) return undefined;
  return collapsed.length > MAX_MESSAGE_LENGTH
    ? `${collapsed.slice(0, MAX_MESSAGE_LENGTH - 1)}…`
    : collapsed;
};

export const classifySaveError = (
  status: number | undefined,
  body: unknown,
): ModelPricesSaveError => {
  if (status === 403) return { kind: "forbidden" };
  if (status === 409) return { kind: "conflict" };
  if (status === 503) return { kind: "disabled" };
  if (status === 422) {
    const problems = problemsOf(body);
    if (problems) return { kind: "invalid", problems };
  }
  return { kind: "unknown", message: cleanMessage(messageOf(body)) };
};

export const SAVE_ERROR_MESSAGES = {
  forbidden: "Only organization admins can change prices",
  conflict:
    "Someone else saved the prices while you were editing. Their version is loaded and your changes are kept on top. Review them and save again.",
  invalid: "Some rows have problems. Fix them and save again.",
  disabled: "Custom model prices are turned off on this installation",
  unknown: "The prices couldn't be saved",
};
