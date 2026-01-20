import { useMediaContext } from "@/components/shared/SyntaxHighlighter/llmMessages";

/**
 * Hook that provides a simple interface to resolve media URLs or placeholders.
 * This centralizes the resolution logic that was previously duplicated across
 * all media block components (Image, Video, Audio).
 *
 * @returns A function that resolves URLs/placeholders to actual media data
 *
 * @example
 * ```tsx
 * const resolveMedia = useMediaResolver();
 * const resolved = resolveMedia("[image_0]", "Fallback Name");
 * // Returns: { url: "actual-url", name: "actual-name" }
 * ```
 */
export const useMediaResolver = () => {
  const { resolveMedia } = useMediaContext();
  return resolveMedia;
};
