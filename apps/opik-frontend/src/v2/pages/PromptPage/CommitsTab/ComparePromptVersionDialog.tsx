import React, { useEffect, useMemo, useState } from "react";
import last from "lodash/last";
import first from "lodash/first";
import isEqual from "fast-deep-equal";
import { Clock, User } from "lucide-react";

import { Sheet, SheetContent, SheetTopBar } from "@/ui/sheet";
import TextDiff from "@/shared/CodeDiff/TextDiff";
import { normalizeChatTemplate } from "@/lib/chatTemplate";
import { PromptVersion } from "@/types/prompts";
import { cn } from "@/lib/utils";
import { formatDate, getTimeFromNow } from "@/lib/date";
import { parseLLMMessageContent, parsePromptVersionContent } from "@/lib/llm";
import MediaTagsList from "@/v2/pages-shared/llm/PromptMessageMediaTags/MediaTagsList";
import VersionTagList from "@/v2/pages-shared/version-history/VersionTagList";
import EnvironmentBadge from "@/shared/EnvironmentLabel/EnvironmentBadge";

type VersionWithMaybeAuthor = PromptVersion & { created_by?: string };

type ComparePromptVersionDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  versions: PromptVersion[];
  initialBaseVersionId?: string;
  initialDiffVersionId?: string;
};

const ComparePromptVersionDialog: React.FunctionComponent<
  ComparePromptVersionDialogProps
> = ({
  open,
  setOpen,
  versions,
  initialBaseVersionId,
  initialDiffVersionId,
}) => {
  const [baseVersion, setBaseVersion] = useState<PromptVersion | undefined>(
    last(versions),
  );
  const [diffVersion, setDiffVersion] = useState<PromptVersion | undefined>(
    first(versions),
  );

  const baseText = useMemo(
    () => normalizeChatTemplate(baseVersion?.template || ""),
    [baseVersion?.template],
  );

  const stringifyMetadata = (m: unknown): string => {
    if (m === undefined || m === null) return "";
    if (typeof m === "object" && Object.keys(m as object).length === 0)
      return "";
    try {
      return JSON.stringify(m, null, 2);
    } catch {
      return String(m);
    }
  };

  const baseMetadataText = useMemo(
    () => stringifyMetadata(baseVersion?.metadata),
    [baseVersion?.metadata],
  );
  const diffMetadataText = useMemo(
    () => stringifyMetadata(diffVersion?.metadata),
    [diffVersion?.metadata],
  );

  const {
    images: baseImages,
    videos: baseVideos,
    audios: baseAudios,
  } = useMemo(() => {
    const content = parsePromptVersionContent(baseVersion);
    return parseLLMMessageContent(content);
  }, [baseVersion]);

  const diffText = useMemo(
    () => normalizeChatTemplate(diffVersion?.template || ""),
    [diffVersion?.template],
  );

  const {
    images: diffImages,
    videos: diffVideos,
    audios: diffAudios,
  } = useMemo(() => {
    const content = parsePromptVersionContent(diffVersion);
    return parseLLMMessageContent(content);
  }, [diffVersion]);

  const imagesHaveChanges = useMemo(
    () => !isEqual(baseImages, diffImages),
    [baseImages, diffImages],
  );
  const videosHaveChanges = useMemo(
    () => !isEqual(baseVideos, diffVideos),
    [baseVideos, diffVideos],
  );
  const audiosHaveChanges = useMemo(
    () => !isEqual(baseAudios, diffAudios),
    [baseAudios, diffAudios],
  );

  const versionLabelByCommit = useMemo(() => {
    const sortedDesc = [...versions].sort((a, b) =>
      b.created_at.localeCompare(a.created_at),
    );
    const total = sortedDesc.length;
    const map = new Map<string, string>();
    sortedDesc.forEach((v, idx) => map.set(v.commit, `v${total - idx}`));
    return map;
  }, [versions]);

  const versionOptions = useMemo(() => {
    return [...versions]
      .sort((v1, v2) => v1.created_at.localeCompare(v2.created_at))
      .map((v) => ({
        label: versionLabelByCommit.get(v.commit) ?? v.commit,
        value: v.commit,
        description: formatDate(v.created_at),
        tags: v.tags || [],
      }));
  }, [versions, versionLabelByCommit]);

  useEffect(() => {
    if (!open) return;
    const requestedBase = initialBaseVersionId
      ? versions.find((v) => v.id === initialBaseVersionId)
      : undefined;
    const requestedDiff = initialDiffVersionId
      ? versions.find((v) => v.id === initialDiffVersionId)
      : undefined;
    setBaseVersion(
      requestedBase ??
        versions.find((v) => v.commit === first(versionOptions)?.value),
    );
    setDiffVersion(
      requestedDiff ??
        versions.find((v) => v.commit === last(versionOptions)?.value),
    );
  }, [
    open,
    versionOptions,
    versions,
    initialBaseVersionId,
    initialDiffVersionId,
  ]);

  const renderVersionColumn = (
    version: PromptVersion | undefined,
    c1: string,
    c2: string,
    side: "base" | "diff",
  ) => {
    if (!version) return null;

    const label = versionLabelByCommit.get(version.commit) ?? version.commit;
    const author = (version as VersionWithMaybeAuthor).created_by;

    return (
      <div className="overflow-hidden rounded-md border bg-background">
        <div className="flex h-8 items-center justify-between gap-2 border-b bg-soft-background px-3">
          <div className="flex min-w-0 items-center gap-2">
            <span className="comet-body-s-accented shrink-0 text-muted-slate">
              {label}
            </span>
            <EnvironmentBadge name={version.environment} size="sm" />
            <VersionTagList tags={version.tags ?? []} size="sm" />
          </div>
          <div className="comet-body-xs flex shrink-0 items-center gap-3 text-light-slate">
            <span className="flex items-center gap-1">
              <Clock className="size-3" />
              {getTimeFromNow(version.created_at)}
            </span>
            {author && (
              <span className="flex items-center gap-1">
                <User className="size-3" />
                {author}
              </span>
            )}
          </div>
        </div>
        <div
          className={cn(
            "overflow-y-auto bg-background p-3",
            imagesHaveChanges ? "max-h-[520px]" : "max-h-[620px]",
          )}
        >
          <div className="comet-code whitespace-pre-line break-words rounded-md border bg-primary-foreground p-3">
            <TextDiff content1={c1} content2={c2} mode="words" side={side} />
          </div>
        </div>
      </div>
    );
  };

  const baseLabel = baseVersion
    ? versionOptions.find((o) => o.value === baseVersion.commit)?.label
    : "";
  const diffLabel = diffVersion
    ? versionOptions.find((o) => o.value === diffVersion.commit)?.label
    : "";
  const sheetTitle =
    baseLabel && diffLabel
      ? `Compare ${baseLabel} → ${diffLabel}`
      : "Compare prompts";

  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetContent
        side="right"
        className="flex w-full max-w-none flex-col p-0 sm:max-w-[960px]"
        header={<SheetTopBar variant="form" title={sheetTitle} />}
      >
        <div className="min-h-0 flex-1 overflow-y-auto p-6">
          <div className="flex flex-col gap-4 pb-2">
            <div className="grid grid-cols-2 [&>*:first-child]:rounded-r-none [&>*:last-child]:-ml-px [&>*:last-child]:rounded-l-none">
              {renderVersionColumn(baseVersion, baseText, diffText, "base")}
              {renderVersionColumn(diffVersion, baseText, diffText, "diff")}
            </div>
            <div>
              <div className="comet-body-s-accented mb-2 text-foreground">
                Metadata
              </div>
              <div className="grid grid-cols-2 [&>*:first-child]:rounded-r-none [&>*:last-child]:-ml-px [&>*:last-child]:rounded-l-none">
                <div className="overflow-hidden rounded-md border bg-background">
                  <div className="max-h-[320px] overflow-y-auto p-3">
                    <div className="comet-code whitespace-pre-line break-words rounded-md border bg-primary-foreground p-3">
                      {baseMetadataText ? (
                        <TextDiff
                          content1={baseMetadataText}
                          content2={diffMetadataText}
                          mode="words"
                          side="base"
                        />
                      ) : (
                        <span className="comet-body-xs text-light-slate">
                          No metadata
                        </span>
                      )}
                    </div>
                  </div>
                </div>
                <div className="overflow-hidden rounded-md border bg-background">
                  <div className="max-h-[320px] overflow-y-auto p-3">
                    <div className="comet-code whitespace-pre-line break-words rounded-md border bg-primary-foreground p-3">
                      {diffMetadataText ? (
                        <TextDiff
                          content1={baseMetadataText}
                          content2={diffMetadataText}
                          mode="words"
                          side="diff"
                        />
                      ) : (
                        <span className="comet-body-xs text-light-slate">
                          No metadata
                        </span>
                      )}
                    </div>
                  </div>
                </div>
              </div>
            </div>
            {(imagesHaveChanges || videosHaveChanges || audiosHaveChanges) && (
              <>
                {imagesHaveChanges && (
                  <div className="grid grid-cols-2 gap-4">
                    <div className="flex min-w-0 flex-wrap items-center gap-2 overflow-hidden rounded-md border p-4">
                      <MediaTagsList
                        type="image"
                        items={baseImages}
                        editable={false}
                        preview={true}
                      />
                    </div>
                    <div className="flex min-w-0 flex-wrap items-center gap-2 overflow-hidden rounded-md border p-4">
                      <MediaTagsList
                        type="image"
                        items={diffImages}
                        editable={false}
                        preview={true}
                      />
                    </div>
                  </div>
                )}
                {videosHaveChanges && (
                  <div className="grid grid-cols-2 gap-4">
                    <div className="flex min-w-0 flex-wrap items-center gap-2 overflow-hidden rounded-md border p-4">
                      <MediaTagsList
                        type="video"
                        items={baseVideos}
                        editable={false}
                        preview={true}
                      />
                    </div>
                    <div className="flex min-w-0 flex-wrap items-center gap-2 overflow-hidden rounded-md border p-4">
                      <MediaTagsList
                        type="video"
                        items={diffVideos}
                        editable={false}
                        preview={true}
                      />
                    </div>
                  </div>
                )}
                {audiosHaveChanges && (
                  <div className="grid grid-cols-2 gap-4">
                    <div className="flex min-w-0 flex-wrap items-center gap-2 overflow-hidden rounded-md border p-4">
                      <MediaTagsList
                        type="audio"
                        items={baseAudios}
                        editable={false}
                        preview={true}
                      />
                    </div>
                    <div className="flex min-w-0 flex-wrap items-center gap-2 overflow-hidden rounded-md border p-4">
                      <MediaTagsList
                        type="audio"
                        items={diffAudios}
                        editable={false}
                        preview={true}
                      />
                    </div>
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      </SheetContent>
    </Sheet>
  );
};

export default ComparePromptVersionDialog;
