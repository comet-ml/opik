/**
 * Scroll-position helpers for preserving a scroll offset across a layout change
 * (e.g. entering/leaving a fullscreen view) where the element's scrollable
 * height differs but the relative position should be kept.
 */

/**
 * Current vertical scroll position of `el` as a 0–1 ratio of its scrollable
 * range. Returns 1 (bottom) when the element isn't scrollable, so restoring the
 * ratio keeps a short log pinned to its latest output.
 */
export const getVerticalScrollRatio = (el: HTMLElement): number => {
  const maxScroll = el.scrollHeight - el.clientHeight;
  return maxScroll > 0 ? el.scrollTop / maxScroll : 1;
};

/**
 * Sets `el`'s vertical scroll position from a 0–1 ratio produced by
 * {@link getVerticalScrollRatio}, mapped onto its current scrollable range.
 */
export const applyVerticalScrollRatio = (
  el: HTMLElement,
  ratio: number,
): void => {
  const maxScroll = el.scrollHeight - el.clientHeight;
  el.scrollTop = maxScroll * ratio;
};
