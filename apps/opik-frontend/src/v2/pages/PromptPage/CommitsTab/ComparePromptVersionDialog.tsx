import React, { useEffect, useMemo, useState } from "react";
import last from "lodash/last";
import first from "lodash/first";
import isEqual from "fast-deep-equal";
import { Clock, LucideIcon, Sparkles, User } from "lucide-react";

import { Sheet, SheetContent, SheetTopBar } from "@/ui/sheet";
import TextDiff from "@/shared/CodeDiff/TextDiff";
import { FormFieldModeSelect } from "@/v2/pages-shared/llm/FormFieldCard";
import { getRoleLabel } from "@/v2/pages-shared/llm/ChatMessageCard/ChatMessageCard";
import { normalizeChatTemplate, parseChatTemplate } from "@/lib/chatTemplate";
import { extractMessageContent } from "@/lib/prompt";
import { PromptVersion } from "@/types/prompts";
import { cn } from "@/lib/utils";
import { formatDate, getTimeFromNow } from "@/lib/date";
import { parseLLMMessageContent, parsePromptVersionContent } from "@/lib/llm";
import MediaTagsList from "@/v2/pages-shared/llm/PromptMessageMediaTags/MediaTagsList";
import VersionTagList from "@/v2/pages-shared/version-history/VersionTagList";
import EnvironmentBadge from "@/shared/EnvironmentLabel/EnvironmentBadge";

type VersionWithMaybeAuthor = PromptVersion & { created_by?: string };
type DiffSide = "base" | "diff";
type ViewMode = "pretty" | "json";
type ChatMessage = { role: string; content: unknown };

const VIEW_MODE_OPTIONS: Array<{
  value: ViewMode;
  label: string;
  icon?: LucideIcon;
}> = [
    { value: "pretty", label: "Pretty", icon: Sparkles },
    { value: "json", label: "JSON" },
  ];

const stringifyMetadata = (m: unknown): string => {
  if (m === undefined || m === null) return "";
  if (typeof m === "object" && Object.keys(m as object).length === 0) return "";
  try {
    return JSON.stringify(m, null, 2);
  } catch {
    return String(m);
  }
};

const SectionContainer: React.FC<{
  title: string;
  actions?: React.ReactNode;
  children: React.ReactNode;
}> = ({ title, actions, children }) => (
  <div>
    <div className="mb-2 flex items-center justify-between gap-2 px-1">
      <span className="comet-body-s text-foreground">{title}</span>
      {actions}
    </div>
    <div className="overflow-hidden rounded-md border border-border bg-background">
      {children}
    </div>
  </div>
);

const ColumnHeader: React.FC<{ version: PromptVersion; label: string }> = ({
  version,
  label,
}) => {
  const author = (version as VersionWithMaybeAuthor).created_by;
  return (
    <div className="flex h-8 min-w-0 items-center justify-between gap-2 bg-soft-background px-3">
      <div className="flex min-w-0 items-center gap-2">
        <span className="comet-body-xs shrink-0 text-muted-slate">{label}</span>
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
  );
};

const HeaderRow: React.FC<{
  baseVersion: PromptVersion;
  diffVersion: PromptVersion;
  baseLabel: string;
  diffLabel: string;
}> = ({ baseVersion, diffVersion, baseLabel, diffLabel }) => (
  <div className="grid grid-cols-2 border-b border-border bg-soft-background">
    <ColumnHeader version={baseVersion} label={baseLabel} />
    <ColumnHeader version={diffVersion} label={diffLabel} />
  </div>
);

const EmptyPlaceholder: React.FC<{ children?: React.ReactNode }> = ({
  children,
}) => (
  <div className="comet-body-s flex min-h-16 items-center justify-center rounded-md border border-dashed border-border bg-transparent px-3 py-2 text-light-slate">
    {children}
  </div>
);

const MessageCell: React.FC<{
  message: ChatMessage | undefined;
  otherMessage: ChatMessage | undefined;
  side: DiffSide;
}> = ({ message, otherMessage, side }) => {
  if (!message) {
    return <EmptyPlaceholder />;
  }

  const isRemoved = side === "base" && !otherMessage;
  const isAdded = side === "diff" && !otherMessage;
  const thisContent = extractMessageContent(message.content);
  const otherContent = otherMessage
    ? extractMessageContent(otherMessage.content)
    : "";
  const baseContent = side === "base" ? thisContent : otherContent;
  const diffContent = side === "base" ? otherContent : thisContent;

  return (
    <div
      className={cn(
        "flex flex-col gap-1 rounded-md border p-2",
        isAdded && "border-transparent bg-[var(--tag-green-bg)]",
        isRemoved && "border-transparent bg-[var(--tag-red-bg)]",
        !isAdded && !isRemoved && "border-border bg-background",
      )}
    >
      <span
        className={cn(
          "comet-body-xs",
          isAdded && "text-[var(--tag-green-text)]",
          isRemoved && "text-[var(--tag-red-text)]",
          !isAdded && !isRemoved && "text-muted-slate",
        )}
      >
        {getRoleLabel(message.role)}
      </span>
      <div
        className={cn(
          "comet-body-s whitespace-pre-wrap break-words",
          isAdded && "text-[var(--tag-green-text)]",
          isRemoved && "text-[var(--tag-red-text)] line-through",
          !isAdded && !isRemoved && "text-foreground",
        )}
      >
        {otherMessage ? (
          <TextDiff
            content1={baseContent}
            content2={diffContent}
            mode="words"
            side={side}
          />
        ) : (
          thisContent
        )}
      </div>
    </div>
  );
};

const MetadataCell: React.FC<{
  text: string;
  baseText: string;
  diffText: string;
  side: DiffSide;
}> = ({ text, baseText, diffText, side }) => {
  if (!text) {
    return <EmptyPlaceholder>No metadata</EmptyPlaceholder>;
  }
  return (
    <div className="comet-code whitespace-pre-line break-words rounded-md border border-border bg-primary-foreground p-2">
      <TextDiff
        content1={baseText}
        content2={diffText}
        mode="words"
        side={side}
      />
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
    const [viewMode, setViewMode] = useState<ViewMode>("pretty");

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
      setViewMode(isChatDiff ? "pretty" : "json");
    }, [
      open,
      versionOptions,
      versions,
      initialBaseVersionId,
      initialDiffVersionId,
      isChatDiff,
    ]);

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
          <div className="min-h-0 flex-1 overflow-y-auto px-6 pb-6">
            <div className="flex flex-col gap-6 pb-2">
              {baseVersion && diffVersion && (
                <SectionContainer
                  title={isChatDiff ? "Chat messages" : "Prompt"}
                  actions={
                    isChatDiff && (
                      <FormFieldModeSelect
                        value={viewMode}
                        options={VIEW_MODE_OPTIONS}
                        onChange={setViewMode}
                      />
                    )
                  }
                >
                  <HeaderRow
                    baseVersion={baseVersion}
                    diffVersion={diffVersion}
                    baseLabel={baseLabel ?? ""}
                    diffLabel={diffLabel ?? ""}
                  />
                  {isChatDiff && viewMode === "pretty" ? (
                    <div className="grid grid-cols-2 gap-2 p-2">
                      {Array.from({
                        length: Math.max(
                          baseChat?.length ?? 0,
                          diffChat?.length ?? 0,
                        ),
                      }).flatMap((_, i) => [
                        <MessageCell
                          key={`base-${i}`}
                          message={baseChat?.[i]}
                          otherMessage={diffChat?.[i]}
                          side="base"
                        />,
                        <MessageCell
                          key={`diff-${i}`}
                          message={diffChat?.[i]}
                          otherMessage={baseChat?.[i]}
                          side="diff"
                        />,
                      ])}
                    </div>
                  ) : (
                    <div className="grid grid-cols-2 gap-2 p-2">
                      <div className="comet-code whitespace-pre-line break-words rounded-md border border-border bg-primary-foreground p-2">
                        <TextDiff
                          content1={baseText}
                          content2={diffText}
                          mode="words"
                          side="base"
                        />
                      </div>
                      <div className="comet-code whitespace-pre-line break-words rounded-md border border-border bg-primary-foreground p-2">
                        <TextDiff
                          content1={baseText}
                          content2={diffText}
                          mode="words"
                          side="diff"
                        />
                      </div>
                    </div>
                  )}
                </SectionContainer>
              )}

              {baseVersion && diffVersion && (
                <SectionContainer title="Metadata">
                  <HeaderRow
                    baseVersion={baseVersion}
                    diffVersion={diffVersion}
                    baseLabel={baseLabel ?? ""}
                    diffLabel={diffLabel ?? ""}
                  />
                  <div className="grid grid-cols-2 gap-2 p-2">
                    <MetadataCell
                      text={baseMetadataText}
                      baseText={baseMetadataText}
                      diffText={diffMetadataText}
                      side="base"
                    />
                    <MetadataCell
                      text={diffMetadataText}
                      baseText={baseMetadataText}
                      diffText={diffMetadataText}
                      side="diff"
                    />
                  </div>
                </SectionContainer>
              )}

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
