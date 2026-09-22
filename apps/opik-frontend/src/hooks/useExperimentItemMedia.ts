import { useEffect, useMemo, useState } from "react";
import { ParsedMediaData } from "@/types/attachments";
import { getAttachmentTypeByMimeType } from "@/constants/attachments";
import useAttachmentsList from "@/api/attachments/useAttachmentsList";
import { processInputData } from "@/lib/images";
import { detectAdditionalMedia } from "@/lib/media";
import { UnifiedMediaItem } from "@/hooks/useUnifiedMedia";

type UseExperimentItemMediaParams = {
  output: object | undefined;
  traceId?: string;
  projectId?: string;
};

export type UseExperimentItemMediaReturn = {
  media: UnifiedMediaItem[];
  transformedOutput: object | undefined;
  isLoading: boolean;
};

/**
 * Resolves the media shown alongside an experiment item's output.
 *
 * Experiment items are not traces, so they cannot go through useUnifiedMedia:
 * they carry no project_id of their own, and isObjectSpan would misclassify them
 * as spans because they expose a trace_id. Attachments are therefore requested
 * for the originating trace explicitly.
 */
export const useExperimentItemMedia = ({
  output,
  traceId,
  projectId,
}: UseExperimentItemMediaParams): UseExperimentItemMediaReturn => {
  const { media: inlineMedia, formattedData } = useMemo(
    () => processInputData(output),
    [output],
  );

  // Extension-less URLs can only be classified by fetching their Content-Type,
  // so inline extraction alone misses them. The effect is keyed on a stable
  // signature of the output rather than on the arrays it produces: storing the
  // result alongside the key it was computed for keeps the resolved media out
  // of the effect's own dependencies, which would otherwise re-trigger it.
  const detectionKey = useMemo(
    () => (output ? JSON.stringify(output) : ""),
    [output],
  );

  const [asyncDetection, setAsyncDetection] = useState<{
    key: string;
    media: ParsedMediaData[];
  } | null>(null);

  useEffect(() => {
    if (!output) {
      return;
    }

    let cancelled = false;

    detectAdditionalMedia(output, inlineMedia)
      .then((result) => {
        if (!cancelled) {
          setAsyncDetection({ key: detectionKey, media: result });
        }
      })
      .catch((error) => {
        // Async detection is an enhancement over inline extraction; failing it
        // must not drop the media already extracted synchronously.
        console.warn("Async media detection failed:", error);
      });

    return () => {
      cancelled = true;
    };
    // inlineMedia is derived from output, so detectionKey already covers it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [detectionKey]);

  const detectedMedia =
    asyncDetection?.key === detectionKey ? asyncDetection.media : inlineMedia;

  const attachmentsParams = useMemo(
    () => ({
      projectId: projectId as string,
      id: traceId as string,
      type: "trace" as const,
      page: 1,
      size: 1000,
    }),
    [projectId, traceId],
  );

  const { data: attachmentsData, isLoading } = useAttachmentsList(
    attachmentsParams,
    { enabled: Boolean(projectId && traceId) },
  );

  const media = useMemo(() => {
    const inline: UnifiedMediaItem[] = detectedMedia.map((item, index) => ({
      id: `inline-output-${index}`,
      url: item.url,
      name: item.name,
      type: item.type,
      source: "inline" as const,
      ...(item.hasPlaceholder && {
        placeholder: `[${item.type}_${index}]`,
      }),
    }));

    const apiMedia: UnifiedMediaItem[] = (attachmentsData?.content ?? []).map(
      (att, index) => ({
        id: `api-${att.file_name}-${index}`,
        placeholder: `[${att.file_name}]`,
        url: att.link,
        name: att.file_name,
        type: getAttachmentTypeByMimeType(att.mime_type),
        source: "attachment" as const,
      }),
    );

    return [...inline, ...apiMedia];
  }, [detectedMedia, attachmentsData]);

  return {
    media,
    transformedOutput: formattedData ?? output,
    isLoading,
  };
};
