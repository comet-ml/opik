import { useMemo, useState, useEffect } from "react";
import { Span, Trace } from "@/types/traces";
import { ATTACHMENT_TYPE, ParsedMediaData } from "@/types/attachments";
import { MINE_TYPE_TO_ATTACHMENT_TYPE_MAP } from "@/constants/attachments";
import { processInputDataInternal } from "@/lib/images";
import { isObjectSpan } from "@/lib/traces";
import useAttachmentsList from "@/api/attachments/useAttachmentsList";
import { detectAdditionalMedia } from "@/lib/media";

/**
 * Unified media item that combines inline media and API attachments
 */
export interface UnifiedMediaItem {
  id: string; // Unique identifier
  url: string; // Actual URL (base64 or link)
  name: string;
  type: ATTACHMENT_TYPE;
  source: "inline" | "attachment"; // Where it came from
  placeholder?: string; // e.g., "[image_0]" for inline media
}

/**
 * Media types that support async detection via URL fetching
 */
const DETECTABLE_TYPES = [
  ATTACHMENT_TYPE.IMAGE,
  ATTACHMENT_TYPE.VIDEO,
  ATTACHMENT_TYPE.AUDIO,
] as const;

/**
 * Creates a single unified media item from parsed media data.
 * Single source of truth for transforming ParsedMediaData → UnifiedMediaItem.
 *
 * @param item - Parsed media data from processInputData
 * @param source - Where the media came from ('inline' or 'attachment')
 * @param idPrefix - Prefix for the unique ID (e.g., 'inline-input', 'async-output')
 * @param index - Index in the array for unique ID generation
 * @param placeholderIndex - Index to use in placeholder text (defaults to index)
 * @returns Unified media item with all required fields
 *
 * @example
 * ```ts
 * const item = createUnifiedMediaItem(
 *   { url: 'data:image/png...', name: 'photo.png', type: ATTACHMENT_TYPE.IMAGE },
 *   'inline',
 *   'inline-input',
 *   0
 * );
 * // Returns: { id: 'inline-input-0', url: '...', name: 'photo.png', type: 'image', source: 'inline', placeholder: '[image_0]' }
 * ```
 */
const createUnifiedMediaItem = (
  item: ParsedMediaData,
  source: "inline" | "attachment",
  idPrefix: string,
  index: number,
  placeholderIndex: number = index,
): UnifiedMediaItem => {
  return {
    id: `${idPrefix}-${index}`,
    url: item.url,
    name: item.name,
    type: item.type,
    source,
    // Use the explicit flag set during extraction in images.ts
    // Only media that was replaced with a placeholder in the JSON gets this field
    ...(item.hasPlaceholder && {
      placeholder: `[${item.type}_${placeholderIndex}]`,
    }),
  };
};

/**
 * Filters and converts unified media items to detectable format for async detection.
 * Only includes media types that support URL-based detection (IMAGE, VIDEO, AUDIO).
 *
 * @param items - Array of unified media items
 * @returns Array of parsed media data ready for async detection
 *
 * @example
 * ```ts
 * const detectable = toDetectableMedia([
 *   { type: ATTACHMENT_TYPE.IMAGE, url: '...', name: 'img.png', ... },
 *   { type: ATTACHMENT_TYPE.OTHER, url: '...', name: 'doc.pdf', ... }
 * ]);
 * // Returns: [{ url: '...', name: 'img.png', type: 'image' }]
 * // (OTHER type filtered out)
 * ```
 */
const toDetectableMedia = (items: UnifiedMediaItem[]): ParsedMediaData[] =>
  items
    .filter((item) =>
      DETECTABLE_TYPES.includes(item.type as (typeof DETECTABLE_TYPES)[number]),
    )
    .map(({ url, name, type }) => ({ url, name, type }) as ParsedMediaData);

/**
 * Converts a batch of parsed media items to unified media items.
 * Handles index offsetting for async-detected media that needs to continue numbering.
 *
 * @param items - Array of parsed media data
 * @param source - Where the media came from
 * @param idPrefix - Prefix for unique IDs
 * @param startIndex - Starting index for placeholder numbering (for async media)
 * @returns Array of unified media items
 *
 * @example
 * ```ts
 * // For async-detected media that should continue from index 3
 * const unified = convertMediaBatch(
 *   asyncDetectedMedia,
 *   'inline',
 *   'async-input',
 *   3 // Start placeholders at [image_3], [image_4], etc.
 * );
 * ```
 */
const convertMediaBatch = (
  items: ParsedMediaData[],
  source: "inline" | "attachment",
  idPrefix: string,
  startIndex: number = 0,
): UnifiedMediaItem[] =>
  items.map((item, index) =>
    createUnifiedMediaItem(item, source, idPrefix, index, startIndex + index),
  );

/**
 * State for async media detection
 */
interface AsyncMediaState {
  media: UnifiedMediaItem[];
  isDetecting: boolean;
  error: Error | null;
}

/**
 * Hook that fetches and merges all media from both inline data and API attachments.
 *
 * This hook provides a unified interface for accessing all media associated with a trace or span:
 * 1. Synchronously extracts media from input/output data (base64 images, etc.)
 * 2. Asynchronously detects additional media from URLs in the data
 * 3. Fetches media attachments from the API
 * 4. Merges and deduplicates all sources into a single array
 *
 * The hook also transforms input/output data by replacing media URLs with placeholders
 * (e.g., [image_0], [video_1]) for consistent rendering.
 *
 * @param data - Trace or Span data containing input/output and media references
 * @returns Object containing:
 *   - media: Unified array of all media items from all sources
 *   - transformedInput: Input data with media URLs replaced by placeholders
 *   - transformedOutput: Output data with media URLs replaced by placeholders
 *   - isLoading: True if either API attachments or async detection is in progress
 *
 * @example
 * ```tsx
 * const { media, transformedInput, transformedOutput, isLoading } = useUnifiedMedia(traceData);
 *
 * // Access all media items
 * media.forEach(item => {
 *   console.log(item.url, item.type, item.source);
 * });
 *
 * // Use transformed data for rendering
 * <SyntaxHighlighter data={transformedInput} />
 * ```
 */
export const useUnifiedMedia = (
  data: Trace | Span,
): {
  media: UnifiedMediaItem[];
  transformedInput: object;
  transformedOutput: object;
  isLoading: boolean;
} => {
  // 1. Process input and output data in a single pass (synchronous)
  const extractedMediaData = useMemo(() => {
    // Use internal function to preserve all placeholders (no deduplication)
    // This is critical for LLM message components where [image_0] and [image_1]
    // may resolve to the same URL but need to be displayed separately
    const inputResult = processInputDataInternal(data.input);
    const outputResult = processInputDataInternal(data.output);

    // Convert to UnifiedMediaItem format with placeholders using helper
    const inputMedia = convertMediaBatch(
      inputResult.media,
      "inline",
      "inline-input",
    );

    const outputMedia = convertMediaBatch(
      outputResult.media,
      "inline",
      "inline-output",
    );

    return {
      inputMedia,
      outputMedia,
      inputMaxIndex: inputResult.media.length,
      outputMaxIndex: outputResult.media.length,
      transformedInput: inputResult.formattedData ?? data.input,
      transformedOutput: outputResult.formattedData ?? data.output,
    };
  }, [data.input, data.output]);

  // 2. Async detection state with error handling
  const [asyncMediaState, setAsyncMediaState] = useState<AsyncMediaState>({
    media: [],
    isDetecting: false,
    error: null,
  });

  // 3. Async detection effect - detects additional media from URLs
  useEffect(() => {
    const abortController = new AbortController();

    setAsyncMediaState((prev) => ({
      ...prev,
      isDetecting: true,
      error: null,
    }));

    const detectAsync = async () => {
      try {
        // Convert to detectable format using helper
        const inputParsedMedia = toDetectableMedia(
          extractedMediaData.inputMedia,
        );
        const outputParsedMedia = toDetectableMedia(
          extractedMediaData.outputMedia,
        );

        // Detect additional media from input and output
        const [inputMediaDetected, outputMediaDetected] = await Promise.all([
          detectAdditionalMedia(data.input, inputParsedMedia),
          detectAdditionalMedia(data.output, outputParsedMedia),
        ]);

        if (!abortController.signal.aborted) {
          // Convert newly detected media (skip already processed items)
          const asyncInputMedia = convertMediaBatch(
            inputMediaDetected.slice(extractedMediaData.inputMaxIndex),
            "inline",
            "async-input",
            extractedMediaData.inputMaxIndex,
          );

          const asyncOutputMedia = convertMediaBatch(
            outputMediaDetected.slice(extractedMediaData.outputMaxIndex),
            "inline",
            "async-output",
            extractedMediaData.outputMaxIndex,
          );

          setAsyncMediaState({
            media: [...asyncInputMedia, ...asyncOutputMedia],
            isDetecting: false,
            error: null,
          });
        }
      } catch (error) {
        if (!abortController.signal.aborted) {
          const errorObj =
            error instanceof Error
              ? error
              : new Error("Async media detection failed");
          console.warn("Async media detection failed:", errorObj);
          setAsyncMediaState({
            media: [],
            isDetecting: false,
            error: errorObj,
          });
        }
      }
    };

    detectAsync();

    return () => {
      abortController.abort();
    };
  }, [
    data.input,
    data.output,
    extractedMediaData.inputMedia,
    extractedMediaData.outputMedia,
    extractedMediaData.inputMaxIndex,
    extractedMediaData.outputMaxIndex,
  ]);

  // 4. Fetch API attachments
  const { data: attachmentsData, isLoading: isLoadingAttachments } =
    useAttachmentsList(
      {
        projectId: data.project_id,
        id: data.id,
        type: isObjectSpan(data) ? "span" : "trace",
        page: 1,
        size: 1000,
      },
      {
        enabled: Boolean(data.project_id && data.id),
      },
    );

  // 5. Merge and deduplicate all media (sync + async + API)
  const media = useMemo(() => {
    const apiMedia: UnifiedMediaItem[] = (attachmentsData?.content ?? []).map(
      (att, index) => ({
        id: `api-${att.file_name}-${index}`,
        placeholder: `[${att.file_name}]`,
        url: att.link,
        name: att.file_name,
        type:
          MINE_TYPE_TO_ATTACHMENT_TYPE_MAP[att.mime_type] ??
          ATTACHMENT_TYPE.OTHER,
        source: "attachment" as const,
      }),
    );

    // Combine all media sources - no deduplication here
    // Consumers (AttachmentsList, ImagesListWrapper) will deduplicate if needed
    return [
      ...extractedMediaData.inputMedia,
      ...extractedMediaData.outputMedia,
      ...asyncMediaState.media,
      ...apiMedia,
    ];
  }, [extractedMediaData, asyncMediaState.media, attachmentsData]);

  return {
    media,
    transformedInput: extractedMediaData.transformedInput,
    transformedOutput: extractedMediaData.transformedOutput,
    isLoading: isLoadingAttachments || asyncMediaState.isDetecting,
  };
};
