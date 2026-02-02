import React, {
  createContext,
  useContext,
  useCallback,
  ReactNode,
} from "react";
import { UnifiedMediaItem } from "@/hooks/useUnifiedMedia";
import { isPlaceholder } from "./utils";
import { isBackendAttachmentPlaceholder } from "@/lib/images";

/**
 * Media context value providing unified media access and resolution utilities
 */
interface MediaContextValue {
  media: UnifiedMediaItem[];
  resolveMedia: (
    urlOrPlaceholder: string,
    fallbackName?: string,
  ) => {
    url: string;
    name: string;
  };
}

/**
 * Context for providing unified media data throughout the component tree.
 * This eliminates the need for components to fetch attachments themselves.
 */
const MediaContext = createContext<MediaContextValue>({
  media: [],
  resolveMedia: (urlOrPlaceholder: string, fallbackName?: string) => ({
    url: urlOrPlaceholder,
    name: fallbackName ?? urlOrPlaceholder,
  }),
});

/**
 * Hook to access media context
 */
export const useMediaContext = (): MediaContextValue => {
  return useContext(MediaContext);
};

interface MediaProviderProps {
  media: UnifiedMediaItem[];
  children: ReactNode;
}

/**
 * Provider component that makes unified media available to all children.
 * Use this at the top level (e.g., InputOutputTab) to provide media data
 * to all nested components without prop drilling.
 *
 * @example
 * ```tsx
 * const { media, transformedInput } = useUnifiedMedia(data);
 * return (
 *   <MediaProvider media={media}>
 *     <SyntaxHighlighter data={transformedInput} />
 *   </MediaProvider>
 * );
 * ```
 */
export const MediaProvider: React.FC<MediaProviderProps> = ({
  media,
  children,
}) => {
  /**
   * Resolves a URL or placeholder to actual media URL and name.
   * This is the single source of truth for media resolution.
   */
  const resolveMedia = useCallback(
    (urlOrPlaceholder: string, fallbackName?: string) => {
      // Check if it's a placeholder like "[image_0]"
      if (
        isPlaceholder(urlOrPlaceholder) ||
        isBackendAttachmentPlaceholder(urlOrPlaceholder)
      ) {
        const mediaItem = media.find(
          (item) => item.placeholder === urlOrPlaceholder,
        );
        if (mediaItem) {
          return {
            url: mediaItem.url,
            name: mediaItem.name,
          };
        }
      }

      // Check if it's a known URL in our media context
      const mediaItem = media.find((item) => item.url === urlOrPlaceholder);
      if (mediaItem) {
        return {
          url: mediaItem.url,
          name: mediaItem.name,
        };
      }

      // Use as-is (e.g., external URL or base64)
      return {
        url: urlOrPlaceholder,
        name: fallbackName ?? urlOrPlaceholder,
      };
    },
    [media],
  );

  return (
    <MediaContext.Provider value={{ media, resolveMedia }}>
      {children}
    </MediaContext.Provider>
  );
};
