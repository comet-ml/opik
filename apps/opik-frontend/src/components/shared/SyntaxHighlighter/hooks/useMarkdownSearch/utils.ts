export const escapeRegexSpecialChars = (text: string): string => {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
};

export const createSearchRegex = (searchTerm: string): RegExp => {
  return new RegExp(`(${escapeRegexSpecialChars(searchTerm)})`, "gi");
};

export const scrollToMatchByIndex = (
  container: HTMLElement | null,
  matchIndex: number,
): void => {
  if (!container) return;

  const element = container.querySelector(`[data-match-index="${matchIndex}"]`);
  if (element) {
    element.scrollIntoView({ block: "center" });
  }
};
