import { TextMarkdownWidget } from "@/types/dashboard";
import trim from "lodash/trim";
import isEmpty from "lodash/isEmpty";
import truncate from "lodash/truncate";
import compact from "lodash/compact";
import find from "lodash/find";

const DEFAULT_TITLE = "Text";
const MAX_TITLE_LENGTH = 100;

const MARKDOWN_HEADING_REGEX = /^#{1,6}\s+(.+)$/;
const MARKDOWN_HEADING_LINE_REGEX = /^#{1,6}\s+.+$/;
const MARKDOWN_SYMBOLS_AT_START_REGEX = /^[#*_`~\-=]+\s*/;
const MARKDOWN_INLINE_FORMATTING_REGEX = /[*_`~]+/g;
const MARKDOWN_LINK_REGEX = /\[([^\]]+)\]\([^)]+\)/g;

const removeMarkdownFormatting = (text: string): string => {
  return trim(
    text
      .replace(MARKDOWN_SYMBOLS_AT_START_REGEX, "")
      .replace(MARKDOWN_INLINE_FORMATTING_REGEX, "")
      .replace(MARKDOWN_LINK_REGEX, "$1"),
  );
};

const calculateTextMarkdownTitle = (
  config: Record<string, unknown>,
): string => {
  const widgetConfig = config as TextMarkdownWidget["config"];
  const content = widgetConfig.content;

  if (isEmpty(trim(content))) {
    return DEFAULT_TITLE;
  }

  const lines = compact(content!.split("\n").map((line) => trim(line)));

  if (isEmpty(lines)) {
    return DEFAULT_TITLE;
  }

  const headingLine = find(lines, (line) =>
    MARKDOWN_HEADING_LINE_REGEX.test(line),
  );

  if (headingLine) {
    const headingMatch = headingLine.match(MARKDOWN_HEADING_REGEX);
    if (headingMatch) {
      return truncate(trim(headingMatch[1]), { length: MAX_TITLE_LENGTH });
    }
  }

  const firstLine = lines[0];
  const cleanText = removeMarkdownFormatting(firstLine);

  if (isEmpty(cleanText)) {
    return DEFAULT_TITLE;
  }

  return truncate(cleanText, { length: MAX_TITLE_LENGTH });
};

export const widgetHelpers = {
  getDefaultConfig: () => ({}),
  calculateTitle: calculateTextMarkdownTitle,
};
