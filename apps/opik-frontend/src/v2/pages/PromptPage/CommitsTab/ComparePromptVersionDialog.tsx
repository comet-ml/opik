import React, { useEffect, useMemo, useState } from "react";
import last from "lodash/last";
import first from "lodash/first";
import isEqual from "fast-deep-equal";
import { ChevronDown, Clock, User } from "lucide-react";

import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { Sheet, SheetContent, SheetTopBar } from "@/ui/sheet";
import TextDiff from "@/shared/CodeDiff/TextDiff";
import PromptDiff from "@/shared/CodeDiff/PromptDiff";
import { normalizeChatTemplate, parseChatTemplate } from "@/lib/chatTemplate";
import { PromptVersion } from "@/types/prompts";
import { cn } from "@/lib/utils";
import { formatDate, getTimeFromNow } from "@/lib/date";
import { parseLLMMessageContent, parsePromptVersionContent } from "@/lib/llm";
import MediaTagsList from "@/v2/pages-shared/llm/PromptMessageMediaTags/MediaTagsList";
import VersionTagList from "@/v2/pages-shared/version-history/VersionTagList";
import EnvironmentBadge from "@/shared/EnvironmentLabel/EnvironmentBadge";

type VersionWithMaybeAuthor = PromptVersion & { created_by?: string };
type DiffSide = "base" | "diff";
type ViewMode = "messages" | "raw";

const TWO_COLUMN_GRID =
  "grid grid-cols-2 [&>*:first-child]:rounded-r-none [&>*:last-child]:-ml-px [&>*:last-child]:rounded-l-none";

const stringifyMetadata = (m: unknown): string => {
  if (m === undefined || m === null) return "";
  if (typeof m === "object" && Object.keys(m as object).length === 0) return "";
  try {
    return JSON.stringify(m, null, 2);
  } catch {
    return String(m);
  }
};

const MetadataColumn: React.FC<{
  baseText: string;
  diffText: string;
  side: DiffSide;
}> = ({ baseText, diffText, side }) => {
  const text = side === "base" ? baseText : diffText;
  return (
    <div className="overflow-hidden rounded-md border bg-background">
      <div className="max-h-[320px] overflow-y-auto p-2">
        <div className="comet-code whitespace-pre-line break-words rounded-md border bg-primary-foreground p-2">
          {text ? (
            <TextDiff
              content1={baseText}
              content2={diffText}
              mode="words"
              side={side}
            />
          ) : (
            <span className="comet-body-xs text-light-slate">No metadata</span>
          )}
        </div>
      </div>
    </div>
  );
};

const MEDIA_KINDS = ["image", "video", "audio"] as const;
type MediaKind = (typeof MEDIA_KINDS)[number];

const MediaRow: React.FC<{
  kind: MediaKind;
  baseItems: string[];
  diffItems: string[];
}> = ({ kind, baseItems, diffItems }) => (
  <div className="grid grid-cols-2 gap-4">
    {[baseItems, diffItems].map((items, idx) => (
      <div
        key={idx}
        className="flex min-w-0 flex-wrap items-center gap-2 overflow-hidden rounded-md border p-4"
      >
        <MediaTagsList type={kind} items={items} editable={false} preview />
      </div>
    ))}
  </div>
);

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
  const [viewMode, setViewMode] = useState<ViewMode>("messages");

  const baseText = useMemo(
    () => normalizeChatTemplate(baseVersion?.template || ""),
    [baseVersion?.template],
  );
  const diffText = useMemo(
    () => normalizeChatTemplate(diffVersion?.template || ""),
    [diffVersion?.template],
  );

  const baseChat = useMemo(() => parseChatTemplate(baseText), [baseText]);
  const diffChat = useMemo(() => parseChatTemplate(diffText), [diffText]);
  const isChatDiff = baseChat !== null && diffChat !== null;

  const baseMetadataText = useMemo(
    () => stringifyMetadata(baseVersion?.metadata),
    [baseVersion?.metadata],
  );
  const diffMetadataText = useMemo(
    () => stringifyMetadata(diffVersion?.metadata),
    [diffVersion?.metadata],
  );

  const baseMedia = useMemo(
    () => parseLLMMessageContent(parsePromptVersionContent(baseVersion)),
    [baseVersion],
  );
  const diffMedia = useMemo(
    () => parseLLMMessageContent(parsePromptVersionContent(diffVersion)),
    [diffVersion],
  );
  const mediaChanges = useMemo(
    () =>
      MEDIA_KINDS.map((kind) => ({
        kind,
        base: baseMedia[`${kind}s` as const],
        diff: diffMedia[`${kind}s` as const],
        changed: !isEqual(
          baseMedia[`${kind}s` as const],
          diffMedia[`${kind}s` as const],
        ),
      })),
    [baseMedia, diffMedia],
  );
  const anyMediaChanged = mediaChanges.some((m) => m.changed);

  const versionLabelByCommit = useMemo(() => {
    const sortedDesc = [...versions].sort((a, b) =>
      b.created_at.localeCompare(a.created_at),
    );
    const total = sortedDesc.length;
    const map = new Map<string, string>();
    sortedDesc.forEach((v, idx) => map.set(v.commit, `v${total - idx}`));
    return map;
  }, [versions]);

  const versionOptions = useMemo(
    () =>
      [...versions]
        .sort((v1, v2) => v1.created_at.localeCompare(v2.created_at))
        .map((v) => ({
          label: versionLabelByCommit.get(v.commit) ?? v.commit,
          value: v.commit,
          description: formatDate(v.created_at),
          tags: v.tags || [],
        })),
    [versions, versionLabelByCommit],
  );

  // Reset selection and view mode each time the sheet reopens.
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
    setViewMode(isChatDiff ? "messages" : "raw");
  }, [
    open,
    versionOptions,
    versions,
    initialBaseVersionId,
    initialDiffVersionId,
    isChatDiff,
  ]);

  const renderTemplateColumn = (version: PromptVersion | undefined, side: DiffSide) => {
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
            "overflow-y-auto bg-background p-2",
            anyMediaChanged ? "max-h-[520px]" : "max-h-[620px]",
          )}
        >
          <div className="comet-code whitespace-pre-line break-words rounded-md border bg-primary-foreground p-2">
            <TextDiff
              content1={baseText}
              content2={diffText}
              mode="words"
              side={side}
            />
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
            {isChatDiff && (
              <div className="flex items-center justify-end">
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button
                      variant="ghost"
                      size="2xs"
                      className="comet-body-xs gap-1 px-2 text-muted-slate"
                    >
                      {viewMode === "messages" ? "Messages" : "Raw"}
                      <ChevronDown className="size-3 text-light-slate" />
                    </Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="end">
                    <DropdownMenuItem
                      size="sm"
                      selected={viewMode === "messages"}
                      onClick={() => setViewMode("messages")}
                    >
                      Messages
                    </DropdownMenuItem>
                    <DropdownMenuItem
                      size="sm"
                      selected={viewMode === "raw"}
                      onClick={() => setViewMode("raw")}
                    >
                      Raw
                    </DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
              </div>
            )}

            {isChatDiff && viewMode === "messages" ? (
              <div className="overflow-hidden rounded-md border bg-background p-4">
                <PromptDiff baseline={baseChat} current={diffChat} />
              </div>
            ) : (
              <div className={TWO_COLUMN_GRID}>
                {renderTemplateColumn(baseVersion, "base")}
                {renderTemplateColumn(diffVersion, "diff")}
              </div>
            )}

            <div>
              <div className="comet-body-s-accented mb-2 text-foreground">
                Metadata
              </div>
              <div className={TWO_COLUMN_GRID}>
                <MetadataColumn
                  baseText={baseMetadataText}
                  diffText={diffMetadataText}
                  side="base"
                />
                <MetadataColumn
                  baseText={baseMetadataText}
                  diffText={diffMetadataText}
                  side="diff"
                />
              </div>
            </div>

            {anyMediaChanged && (
              <>
                {mediaChanges
                  .filter((m) => m.changed)
                  .map((m) => (
                    <MediaRow
                      key={m.kind}
                      kind={m.kind}
                      baseItems={m.base}
                      diffItems={m.diff}
                    />
                  ))}
              </>
            )}
          </div>
        </div>
      </SheetContent>
    </Sheet>
  );
};

export default ComparePromptVersionDialog;
