import { useState, useEffect } from "react";

/**
 * Custom hook to match CSS media queries in JavaScript.
 * Updates automatically when the media query match changes.
 *
 * @param query - CSS media query string (e.g., '(max-width: 1023px)')
 * @returns boolean indicating if the media query matches
 *
 * @example
 * const isMobile = useMediaQuery('(max-width: 1023px)');
 * const isPortrait = useMediaQuery('(orientation: portrait)');
 */
export const useMediaQuery = (query: string): boolean => {
  const [matches, setMatches] = useState(false);

  useEffect(() => {
    const mediaQuery = window.matchMedia(query);

    setMatches(mediaQuery.matches);

    const handleChange = (e: MediaQueryListEvent) => {
      setMatches(e.matches);
    };

    mediaQuery.addEventListener("change", handleChange);

    return () => mediaQuery.removeEventListener("change", handleChange);
  }, [query]);

  return matches;
};
