import isBoolean from "lodash/isBoolean";
import isNumber from "lodash/isNumber";
import isObjectLike from "lodash/isObjectLike";

const TYPING_PAUSE_MS = 500;
const WORD_END_REGEX = /[\s.,;:!?]$/;

type EditKind = "text" | "number" | "toggle";

export type Edit = {
  kind: EditKind;
  at: number;
  isDeleting: boolean;
  endsWord: boolean;
};

export const toText = (value: unknown): string =>
  isObjectLike(value)
    ? Object.values(value as object)
        .map(toText)
        .join("\n")
    : String(value ?? "");

const getEditKind = (value: unknown): EditKind => {
  if (isBoolean(value)) return "toggle";
  if (isNumber(value)) return "number";
  return "text";
};

const getInsertedText = (previous: string, next: string) => {
  let start = 0;
  while (start < previous.length && previous[start] === next[start]) start++;
  let end = 0;
  while (
    end < previous.length - start &&
    previous[previous.length - 1 - end] === next[next.length - 1 - end]
  ) {
    end++;
  }
  return next.slice(start, next.length - end);
};

export const describeEdit = (
  value: unknown,
  previousText: string,
  nextText: string,
  at = Date.now(),
): Edit => {
  const kind = getEditKind(value);
  const isDeleting = nextText.length < previousText.length;
  const endsWord =
    kind === "text" &&
    !isDeleting &&
    WORD_END_REGEX.test(getInsertedText(previousText, nextText));
  return { kind, at, isDeleting, endsWord };
};

const isToggle = (edit: Edit) => edit.kind === "toggle";

const isAfterPause = (previous: Edit, edit: Edit) =>
  edit.at - previous.at > TYPING_PAUSE_MS;

const changesKind = (previous: Edit, edit: Edit) => previous.kind !== edit.kind;

const changesDirection = (previous: Edit, edit: Edit) =>
  edit.kind === "text" && previous.isDeleting !== edit.isDeleting;

export const startsNewStep = (previous: Edit | null, edit: Edit) =>
  !previous ||
  isToggle(edit) ||
  isAfterPause(previous, edit) ||
  changesKind(previous, edit) ||
  changesDirection(previous, edit) ||
  previous.endsWord;
