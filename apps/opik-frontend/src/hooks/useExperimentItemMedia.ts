import { useMemo } from "react";
import { ATTACHMENT_TYPE } from "@/types/attachments";
import { MINE_TYPE_TO_ATTACHMENT_TYPE_MAP } from "@/constants/attachments";
import useAttachmentsList from "@/api/attachments/useAttachmentsList";
import { processInputData } from "@/lib/images";
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
    const inline: UnifiedMediaItem[] = inlineMedia.map((item, index) => ({
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
        type:
          MINE_TYPE_TO_ATTACHMENT_TYPE_MAP[att.mime_type] ??
          ATTACHMENT_TYPE.OTHER,
        source: "attachment" as const,
      }),
    );

    return [...inline, ...apiMedia];
  }, [inlineMedia, attachmentsData]);

  return {
    media,
    transformedOutput: formattedData ?? output,
    isLoading,
  };
};
