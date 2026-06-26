import React from "react";
import { CellContext } from "@tanstack/react-table";
import {
  CELL_HORIZONTAL_ALIGNMENT,
  CELL_VERTICAL_ALIGNMENT,
  COLUMN_TYPE,
  ROW_HEIGHT,
} from "@/types/shared";

export const DEMO_PROJECT_NAME = "Opik Demo Agent Observability";
export const SNIPPET_PROJECT_NAME = "my-ai-project";
export const PROJECT_NAME_PLACEHOLDER = "PROJECT_NAME_PLACEHOLDER";
export const PLAYGROUND_PROJECT_NAME = "playground";
export const USER_FEEDBACK_NAME = "User feedback";
export const PIP_INSTALL_OPIK_COMMAND = "pip install opik";
export const INSTALL_OPIK_SKILLS_COMMAND =
  "npx skills add comet-ml/opik-skills -g --all";
export const INSTALL_OPIK_SECTION_TITLE =
  "1. Install Opik using pip from the command line";
// Default copy for the install step in onboarding integration dialogs.
// Integrations can override via `installTitle` / `installDescription`.
export const INSTALL_OPIK_DEFAULT_TITLE = `${INSTALL_OPIK_SECTION_TITLE}.`;
export const INSTALL_OPIK_DEFAULT_DESCRIPTION =
  "Install Opik from the command line using pip.";
export const INSTALL_SDK_SECTION_TITLE = "2. Install the SDK";

export const TRUNCATION_DISABLED_MAX_PAGE_SIZE = 10;

export const TABLE_HEADER_Z_INDEX = 2;
export const TABLE_ROW_Z_INDEX = 0;

export const ROW_HEIGHT_MAP: Record<ROW_HEIGHT, React.CSSProperties> = {
  [ROW_HEIGHT.small]: { height: "32px" },
  [ROW_HEIGHT.medium]: { height: "40px" },
  [ROW_HEIGHT.large]: { height: "160px" },
};

export const HEADER_TEXT_CLASS_MAP: Record<ROW_HEIGHT, string> = {
  [ROW_HEIGHT.small]: "comet-body-xs",
  [ROW_HEIGHT.medium]: "comet-body-s",
  [ROW_HEIGHT.large]: "comet-body-s",
};

export const CELL_TEXT_CLASS_MAP: Record<ROW_HEIGHT, string> = {
  [ROW_HEIGHT.small]: "comet-body-xs",
  [ROW_HEIGHT.medium]: "comet-body-s",
  [ROW_HEIGHT.large]: "comet-body-s",
};

export const TAG_SIZE_MAP: Record<ROW_HEIGHT, "default" | "md"> = {
  [ROW_HEIGHT.small]: "default",
  [ROW_HEIGHT.medium]: "md",
  [ROW_HEIGHT.large]: "md",
};

export const FEEDBACK_SCORE_TAG_SIZE_MAP: Record<ROW_HEIGHT, "sm" | "md"> = {
  [ROW_HEIGHT.small]: "sm",
  [ROW_HEIGHT.medium]: "sm",
  [ROW_HEIGHT.large]: "md",
};

export function getCellTagSize<T>(
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  context: CellContext<any, any>,
  sizeMap: Record<ROW_HEIGHT, T>,
): T {
  const rowHeight = context.table.options.meta?.rowHeight ?? ROW_HEIGHT.small;
  return sizeMap[rowHeight];
}

export const CELL_VERTICAL_ALIGNMENT_MAP = {
  [CELL_VERTICAL_ALIGNMENT.start]: "items-start",
  [CELL_VERTICAL_ALIGNMENT.center]: "items-center",
  [CELL_VERTICAL_ALIGNMENT.end]: "items-end",
};

// Single source of truth: the default horizontal alignment a column type renders
// at. Numeric-style values sit on the right; everything else on the left.
export const DEFAULT_HORIZONTAL_ALIGNMENT_BY_TYPE: Record<
  COLUMN_TYPE,
  CELL_HORIZONTAL_ALIGNMENT
> = {
  [COLUMN_TYPE.number]: CELL_HORIZONTAL_ALIGNMENT.end,
  [COLUMN_TYPE.cost]: CELL_HORIZONTAL_ALIGNMENT.end,
  [COLUMN_TYPE.duration]: CELL_HORIZONTAL_ALIGNMENT.end,
  [COLUMN_TYPE.string]: CELL_HORIZONTAL_ALIGNMENT.start,
  [COLUMN_TYPE.list]: CELL_HORIZONTAL_ALIGNMENT.start,
  [COLUMN_TYPE.time]: CELL_HORIZONTAL_ALIGNMENT.start,
  [COLUMN_TYPE.dictionary]: CELL_HORIZONTAL_ALIGNMENT.start,
  [COLUMN_TYPE.numberDictionary]: CELL_HORIZONTAL_ALIGNMENT.start,
  [COLUMN_TYPE.category]: CELL_HORIZONTAL_ALIGNMENT.start,
  [COLUMN_TYPE.errors]: CELL_HORIZONTAL_ALIGNMENT.start,
};

export const CELL_HORIZONTAL_ALIGNMENT_CLASS_MAP: Record<
  CELL_HORIZONTAL_ALIGNMENT,
  string
> = {
  [CELL_HORIZONTAL_ALIGNMENT.start]: "justify-start",
  [CELL_HORIZONTAL_ALIGNMENT.end]: "justify-end",
};

/**
 * Resolve a column's horizontal alignment from its meta. An explicit
 * `horizontalAlignment` override wins; otherwise the per-type default; otherwise
 * `start`. This is the one place alignment is decided — the cell renderer and the
 * Explain button both call it, so a column declares its alignment once (via type
 * or an explicit override) and no consumer hardcodes column types.
 */
export const resolveHorizontalAlignment = (meta?: {
  type?: COLUMN_TYPE;
  horizontalAlignment?: CELL_HORIZONTAL_ALIGNMENT;
}): CELL_HORIZONTAL_ALIGNMENT =>
  meta?.horizontalAlignment ??
  (meta?.type ? DEFAULT_HORIZONTAL_ALIGNMENT_BY_TYPE[meta.type] : undefined) ??
  CELL_HORIZONTAL_ALIGNMENT.start;

// Back-compat lookup by column type (derived from the source of truth above) for
// consumers that only key off `type` (split-cell/header wrappers).
export const CELL_HORIZONTAL_ALIGNMENT_MAP = Object.fromEntries(
  (Object.keys(DEFAULT_HORIZONTAL_ALIGNMENT_BY_TYPE) as COLUMN_TYPE[]).map(
    (type) => [
      type,
      CELL_HORIZONTAL_ALIGNMENT_CLASS_MAP[
        DEFAULT_HORIZONTAL_ALIGNMENT_BY_TYPE[type]
      ],
    ],
  ),
) as Record<COLUMN_TYPE, string>;
