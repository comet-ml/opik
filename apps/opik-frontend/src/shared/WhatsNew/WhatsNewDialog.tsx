import React, { useEffect, useMemo, useState } from "react";
import { SheetDescription, SheetTitle } from "@/ui/sheet";
import { Separator } from "@/ui/separator";
import { Tag } from "@/ui/tag";
import { Spinner } from "@/ui/spinner";
import { cn } from "@/lib/utils";
import { formatDate as formatAppDate } from "@/lib/date";
import SideDialog from "@/shared/SideDialog/SideDialog";
import MarkdownPreview from "@/shared/MarkdownPreview/MarkdownPreview";
import { useLayoutDialog } from "@/hooks/useLayoutDialog";
import useWhatsNewSeen from "@/hooks/useWhatsNewSeen";
import useReleaseNotes from "@/api/deployment/useReleaseNotes";
import useDeploymentVersionHistory from "@/api/deployment/useDeploymentVersionHistory";
import {
  entriesForVersion,
  entriesInRange,
  isDevelopmentVersion,
} from "@/lib/releaseNotes";
import { ReleaseNoteEntry } from "@/types/release-notes";

const useOpenWhatsNewDialog = () => useLayoutDialog("whats-new");

const CURRENT_SELECTION = "__current__";

// Entry dates are date-only (`2026-10-19`); dayjs reads those as a local date, where `new Date`
// would read them as UTC midnight and show the previous day west of Greenwich.
const formatDate = (value?: string | null) =>
  value ? formatAppDate(value, { format: "D MMM YYYY" }) : null;

type VersionHistoryItemProps = {
  label: string;
  description: string | null;
  selected: boolean;
  onClick: () => void;
};

const VersionHistoryItem: React.FC<VersionHistoryItemProps> = ({
  label,
  description,
  selected,
  onClick,
}) => (
  <button
    type="button"
    onClick={onClick}
    className={cn(
      "flex w-full flex-col items-start gap-0.5 rounded-md px-3 py-2 text-left transition-colors hover:bg-primary-foreground",
      selected && "bg-primary-foreground",
    )}
  >
    <span className="comet-body-s-accented truncate">{label}</span>
    {description && (
      <span className="comet-body-xs text-muted-slate">{description}</span>
    )}
  </button>
);

const EmptyState: React.FC<{ title: string; message: string }> = ({
  title,
  message,
}) => (
  <div className="flex h-64 flex-col items-center justify-center rounded-md border bg-background px-6 text-center">
    <h3 className="comet-title-s">{title}</h3>
    <p className="comet-body-s max-w-[440px] pt-2 text-muted-slate">
      {message}
    </p>
  </div>
);

const ReleaseNoteSection: React.FC<{ entry: ReleaseNoteEntry }> = ({
  entry,
}) => (
  <section className="border-b py-6 first:pt-0 last:border-b-0">
    <div className="mb-3 flex items-center gap-2">
      {entry.version ? (
        <Tag size="md" variant="green">
          {entry.version}
        </Tag>
      ) : (
        <Tag size="md" variant="gray">
          Unversioned
        </Tag>
      )}
      <span className="comet-body-s text-muted-slate">
        {formatDate(entry.date)}
      </span>
    </div>
    <MarkdownPreview>{entry.body}</MarkdownPreview>
  </section>
);

const WhatsNewDialog: React.FC = () => {
  const { isOpen, setOpen } = useOpenWhatsNewDialog();
  const [selectedVersion, setSelectedVersion] =
    useState<string>(CURRENT_SELECTION);

  const { data: history, isPending: isHistoryPending } =
    useDeploymentVersionHistory();
  // The manifest is 150KB+ of markdown. Loading it only once the panel is opened keeps it out
  // of the app-boot path entirely.
  const {
    data: entries,
    isPending: isNotesPending,
    isError,
  } = useReleaseNotes({
    enabled: isOpen,
  });

  const currentVersion = history?.current_version;
  const { markSeen } = useWhatsNewSeen(currentVersion);

  useEffect(() => {
    if (isOpen) markSeen();
  }, [isOpen, markSeen]);

  const deployedVersions = useMemo(() => history?.versions ?? [], [history]);

  const visibleEntries = useMemo(() => {
    if (!entries) return [];
    if (selectedVersion === CURRENT_SELECTION) {
      return entriesForVersion(entries, currentVersion);
    }

    // Selecting a version in the history asks "what did that upgrade bring?", so the range runs
    // from the version this install was on *before* it, not from the previous release upstream.
    const index = deployedVersions.findIndex(
      (deployed) => deployed.version === selectedVersion,
    );
    const previous = deployedVersions[index + 1]?.version ?? null;

    return entriesInRange(entries, previous, selectedVersion);
  }, [entries, selectedVersion, currentVersion, deployedVersions]);

  const isPending = isHistoryPending || isNotesPending;

  const renderSubtitle = () => {
    if (!currentVersion) return null;

    const deployedAt = formatDate(
      deployedVersions.find((deployed) => deployed.version === currentVersion)
        ?.first_seen_at,
    );

    if (isDevelopmentVersion(currentVersion)) {
      return "You're on a development build tracking the latest changes, so every update below is included.";
    }

    return `You're on version ${currentVersion}${
      deployedAt ? `, deployed ${deployedAt}` : ""
    }.`;
  };

  const renderContent = () => {
    if (isPending) {
      return (
        <div className="flex h-64 items-center justify-center">
          <Spinner />
        </div>
      );
    }

    if (isError || !entries) {
      return (
        <EmptyState
          title="Release notes aren't available"
          message="This deployment couldn't load its bundled release notes. The version history on the left is still accurate."
        />
      );
    }

    if (visibleEntries.length === 0) {
      return (
        <EmptyState
          title="No release notes for this version"
          message={
            selectedVersion === CURRENT_SELECTION
              ? "No published updates are associated with this version yet."
              : "This upgrade didn't include any user-facing changes, or its notes predate in-product release notes."
          }
        />
      );
    }

    return (
      <div>
        {visibleEntries.map((entry) => (
          <ReleaseNoteSection key={entry.date} entry={entry} />
        ))}
      </div>
    );
  };

  return (
    <SideDialog open={isOpen} setOpen={setOpen}>
      <div className="flex size-full min-w-fit flex-col px-20 pb-20">
        <SheetTitle className="comet-title-xl my-3 text-left">
          What&apos;s new
        </SheetTitle>
        <SheetDescription className="comet-body-s mb-8 text-muted-slate">
          {renderSubtitle()}
        </SheetDescription>

        <div className="flex min-h-0 grow gap-10">
          <aside className="w-64 shrink-0">
            <h3 className="comet-body-s-accented mb-2 px-3">Version history</h3>
            <div className="flex flex-col">
              <VersionHistoryItem
                label="Current version"
                description={currentVersion ?? null}
                selected={selectedVersion === CURRENT_SELECTION}
                onClick={() => setSelectedVersion(CURRENT_SELECTION)}
              />
              <Separator className="my-2" />
              {deployedVersions.map((deployed) => (
                <VersionHistoryItem
                  key={deployed.version}
                  label={deployed.version}
                  description={`Deployed ${formatDate(deployed.first_seen_at)}`}
                  selected={selectedVersion === deployed.version}
                  onClick={() => setSelectedVersion(deployed.version)}
                />
              ))}
            </div>
          </aside>

          <div className="min-w-0 grow overflow-auto">{renderContent()}</div>
        </div>
      </div>
    </SideDialog>
  );
};

export { useOpenWhatsNewDialog };
export default WhatsNewDialog;
