import { useMemo } from "react";
import { Trace } from "@/types/traces";
import { UnifiedMediaItem } from "@/hooks/useUnifiedMedia";
import { processInputDataInternal } from "@/lib/images";
import { ATTACHMENT_TYPE } from "@/types/attachments";

/**
 * Hook that aggregates media from all traces in a thread.
 * Extracts media from input/output of each trace.
 *
 * @param traces - Array of traces in the thread
 * @returns Object containing aggregated media items
 */
export const useThreadMedia = (
  traces: Trace[],
): {
  media: UnifiedMediaItem[];
} => {
  const media = useMemo(() => {
    const allMedia: UnifiedMediaItem[] = [];
    let imageIndex = 0;
    let videoIndex = 0;
    let audioIndex = 0;

    traces.forEach((trace) => {
      // Process input and output for each trace
      const inputResult = processInputDataInternal(trace.input);
      const outputResult = processInputDataInternal(trace.output);

      // Convert input media to UnifiedMediaItem format
      inputResult.media.forEach((item) => {
        const index =
          item.type === ATTACHMENT_TYPE.IMAGE
            ? imageIndex++
            : item.type === ATTACHMENT_TYPE.VIDEO
              ? videoIndex++
              : audioIndex++;

        allMedia.push({
          id: `trace-${trace.id}-input-${index}`,
          url: item.url,
          name: item.name,
          type: item.type,
          source: "inline",
          ...(item.hasPlaceholder && {
            placeholder: `[${item.type}_${index}]`,
          }),
        });
      });

      // Convert output media to UnifiedMediaItem format
      outputResult.media.forEach((item) => {
        const index =
          item.type === ATTACHMENT_TYPE.IMAGE
            ? imageIndex++
            : item.type === ATTACHMENT_TYPE.VIDEO
              ? videoIndex++
              : audioIndex++;

        allMedia.push({
          id: `trace-${trace.id}-output-${index}`,
          url: item.url,
          name: item.name,
          type: item.type,
          source: "inline",
          ...(item.hasPlaceholder && {
            placeholder: `[${item.type}_${index}]`,
          }),
        });
      });
    });

    // Return all media items without deduplication to preserve placeholder mappings
    return allMedia;
  }, [traces]);

  return { media };
};
