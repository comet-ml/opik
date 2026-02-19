import { Attachment } from "@/types/attachments";

/**
 * Check if a string is a placeholder pattern like "[image_0]", "[video_1]", etc.
 */
export const isPlaceholder = (value: string): boolean => {
  return /^\[(image|video|audio)_\d+\]$/.test(value);
};

/**
 * Extract the index from a placeholder string.
 * @example extractPlaceholderIndex("[image_0]") // returns 0
 * @example extractPlaceholderIndex("[video_2]") // returns 2
 */
export const extractPlaceholderIndex = (placeholder: string): number => {
  const match = placeholder.match(/^\[(?:image|video|audio)_(\d+)\]$/);
  return match ? parseInt(match[1], 10) : -1;
};

/**
 * Resolve a single placeholder URL to the actual attachment URL.
 * If the URL is not a placeholder or attachments are not available, returns the original URL.
 */
export const resolvePlaceholderURL = (
  url: string,
  attachments: Attachment[] | undefined,
): string => {
  if (!isPlaceholder(url) || !attachments) return url;

  const index = extractPlaceholderIndex(url);
  return attachments[index]?.link || url;
};

/**
 * Resolve an array of media items with placeholder URLs to actual attachment URLs.
 * Also updates the name from attachment metadata if available.
 */
export const resolveMediaItems = (
  items: Array<{ url: string; name: string }>,
  attachments: Attachment[] | undefined,
): Array<{ url: string; name: string }> => {
  return items.map((item) => {
    if (!isPlaceholder(item.url) || !attachments) return item;

    const index = extractPlaceholderIndex(item.url);
    const attachment = attachments[index];

    return {
      url: attachment?.link || item.url,
      name: attachment?.file_name || item.name,
    };
  });
};
