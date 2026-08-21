export type ColumnWindow = {
  active: boolean;
  leftCount: number;
  centerCount: number;
  start: number;
  end: number;
  leadingWidth: number;
  trailingWidth: number;
};

export const INACTIVE_COLUMN_WINDOW: ColumnWindow = {
  active: false,
  leftCount: 0,
  centerCount: 0,
  start: 0,
  end: -1,
  leadingWidth: 0,
  trailingWidth: 0,
};

// Stands in for the skipped columns, so the table keeps its full width
export type ColumnSpacer = {
  id: string;
  size: number;
  isColumnSpacer: true;
};

export const isColumnSpacer = (item: unknown): item is ColumnSpacer =>
  (item as ColumnSpacer | null)?.isColumnSpacer === true;

const createSpacer = (id: string, size: number): ColumnSpacer => ({
  id,
  size,
  isColumnSpacer: true,
});

// Index based, so colgroup entries, header cells and body cells share one
// slice — they are in the same visual order and desync the layout if they differ
export const sliceColumnWindow = <T>(
  items: T[],
  window: ColumnWindow,
): (T | ColumnSpacer)[] => {
  if (!window.active) return items;

  const { leftCount, centerCount, start, end, leadingWidth, trailingWidth } =
    window;

  return [
    ...items.slice(0, leftCount),
    ...(leadingWidth
      ? [createSpacer("__column_spacer_lead", leadingWidth)]
      : []),
    ...items.slice(leftCount + start, leftCount + end + 1),
    ...(trailingWidth
      ? [createSpacer("__column_spacer_trail", trailingWidth)]
      : []),
    ...items.slice(leftCount + centerCount),
  ];
};
