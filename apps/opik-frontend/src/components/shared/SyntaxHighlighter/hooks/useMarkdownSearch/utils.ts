export const escapeRegexSpecialChars = (text: string): string => {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
};

export const createSearchRegex = (searchTerm: string): RegExp => {
  return new RegExp(`(${escapeRegexSpecialChars(searchTerm)})`, "gi");
};

export const findHighlightByIndex = (
  container: HTMLElement,
  matchIndex: number,
): HTMLElement | null => {
  return container.querySelector(
    `[data-match-index="${matchIndex}"]`,
  ) as HTMLElement | null;
};

export const scrollToMatchByIndex = (
  container: HTMLElement | null,
  matchIndex: number,
): void => {
  if (!container) return;

  const element = findHighlightByIndex(container, matchIndex);
  if (element) {
    element.scrollIntoView({ block: "center" });
  }
};
