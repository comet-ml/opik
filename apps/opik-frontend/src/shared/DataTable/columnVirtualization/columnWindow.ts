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

export type WindowedHeader<THeader> = {
  header: THeader;
  colSpan: number;
  key?: string;
};

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

export const sliceColumnWindowHeaders = <
  THeader extends { id: string; colSpan: number },
>(
  headers: THeader[],
  window: ColumnWindow,
): (WindowedHeader<THeader> | ColumnSpacer)[] => {
  if (!window.active) {
    return headers.map((header) => ({ header, colSpan: header.colSpan }));
  }

  const leafSlots = headers.flatMap((header) =>
    new Array<THeader>(Math.max(header.colSpan, 1)).fill(header),
  );

  const segments = new Map<THeader, number>();

  return sliceColumnWindow(leafSlots, window).reduce<
    (WindowedHeader<THeader> | ColumnSpacer)[]
  >((acc, entry) => {
    if (isColumnSpacer(entry)) {
      acc.push(entry);
      return acc;
    }

    const previous = acc[acc.length - 1];

    if (!isColumnSpacer(previous) && previous?.header === entry) {
      previous.colSpan += 1;
      return acc;
    }

    const segment = (segments.get(entry) ?? 0) + 1;
    segments.set(entry, segment);

    acc.push({
      header: entry,
      colSpan: 1,
      ...(segment > 1 && { key: `${entry.id}__${segment}` }),
    });

    return acc;
  }, []);
};
