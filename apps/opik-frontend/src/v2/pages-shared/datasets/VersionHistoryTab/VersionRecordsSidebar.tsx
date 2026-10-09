import React, { useCallback, useMemo, useState } from "react";
import { RotateCcw } from "lucide-react";
import { StringParam, useQueryParam } from "use-query-params";

import { Sheet, SheetContent, SheetTopBar } from "@/ui/sheet";
import { Button } from "@/ui/button";
import { Tag } from "@/ui/tag";
import Loader from "@/shared/Loader/Loader";
import { PortalContainerProvider } from "@/lib/portal-container";
import { extractAssertions } from "@/lib/assertion-converters";
import { formatDate } from "@/lib/date";
import { usePermissions } from "@/contexts/PermissionsContext";
import useDatasetVersionByHash from "@/api/datasets/useDatasetVersionByHash";
import { DatasetVersion } from "@/types/datasets";
import { isLatestVersionTag } from "@/constants/datasets";
import { EditPanelRenderProps } from "@/v2/pages-shared/datasets/DatasetItemsTab/DatasetItemsTab";
import DatasetItemsView from "@/v2/pages-shared/datasets/DatasetItemsView/DatasetItemsView";
import { DVS_QUERY_PREFIX } from "@/v2/pages-shared/datasets/DatasetItemsView/constants";
import { formatExecutionPolicy } from "@/v2/pages-shared/datasets/DatasetItemsView/SuiteItemCells";
import RestoreVersionDialog from "./RestoreVersionDialog";
import VersionItemPanel from "./VersionItemPanel";
import { useVersionRecordsSidebarControls } from "./useVersionRecordsSidebarControls";

type VersionRecordsSidebarProps = {
  datasetId: string;
  datasetName?: string;
  isTestSuite: boolean;
};

const VersionRecordsSidebar: React.FC<VersionRecordsSidebarProps> = ({
  datasetId,
  datasetName,
  isTestSuite,
}) => {
  const { versionHash, closeVersion: onClose } =
    useVersionRecordsSidebarControls();
  const {
    permissions: { canEditDatasets },
  } = usePermissions();
  const [container, setContainer] = useState<HTMLDivElement | null>(null);
  const [restoreOpen, setRestoreOpen] = useState(false);
  const [activeRowId, setActiveRowId] = useQueryParam(
    `${DVS_QUERY_PREFIX}row`,
    StringParam,
    { updateType: "replaceIn" },
  );

  const open = Boolean(versionHash);

  const { data: version, isError: isVersionError } = useDatasetVersionByHash(
    { datasetId, versionHash: versionHash ?? "" },
    { enabled: open },
  );

  const isLatestVersion = version?.tags?.some(isLatestVersionTag) ?? false;

  const suiteAssertions = useMemo(
    () => extractAssertions(version?.evaluators ?? []),
    [version?.evaluators],
  );

  const renderItemPanel = useCallback(
    (props: EditPanelRenderProps) =>
      version ? (
        <VersionItemPanel
          {...props}
          version={version}
          isTestSuite={isTestSuite}
          itemLabel={isTestSuite ? "Test case" : "Record"}
          container={container}
        />
      ) : null,
    [version, isTestSuite, container],
  );

  const handleOpenChange = (isOpen: boolean) => {
    if (!isOpen) onClose();
  };

  return (
    <Sheet open={open} onOpenChange={handleOpenChange}>
      <SheetContent
        ref={setContainer}
        className="flex w-screen flex-col shadow-none outline-none sm:max-w-full"
        header={
          <SheetTopBar
            variant="info"
            title={
              <span className="flex min-w-0 items-center gap-2">
                <span className="truncate">
                  {[version?.version_name, datasetName]
                    .filter(Boolean)
                    .join(" · ")}
                </span>
                <Tag variant="gray" size="sm" className="shrink-0">
                  Read-only
                </Tag>
              </span>
            }
          >
            {canEditDatasets && version && !isLatestVersion && (
              <Button
                variant="outline"
                size="xs"
                onClick={() => setRestoreOpen(true)}
              >
                <RotateCcw className="mr-1.5 size-3.5" />
                Restore this version
              </Button>
            )}
          </SheetTopBar>
        }
        aria-describedby={undefined}
        onOpenAutoFocus={(e) => e.preventDefault()}
        onEscapeKeyDown={(e) => {
          if (activeRowId) {
            e.preventDefault();
            setActiveRowId("");
          }
        }}
      >
        {version && (
          <RestoreVersionDialog
            open={restoreOpen}
            setOpen={setRestoreOpen}
            datasetId={datasetId}
            version={version}
            onRestored={onClose}
          />
        )}
        <PortalContainerProvider value={container}>
          <div className="flex min-h-0 flex-1 flex-col overflow-y-auto px-6 py-4">
            {version && (
              <VersionMeta
                version={version}
                isTestSuite={isTestSuite}
                suiteAssertions={suiteAssertions}
              />
            )}
            {version ? (
              <DatasetItemsView
                datasetId={datasetId}
                versionId={version.version_hash}
                isTestSuite={isTestSuite}
                renderItemPanel={renderItemPanel}
              />
            ) : isVersionError ? (
              <div className="comet-body-s py-8 text-center text-muted-slate">
                Couldn&apos;t load this version
              </div>
            ) : (
              open && <Loader />
            )}
          </div>
        </PortalContainerProvider>
      </SheetContent>
    </Sheet>
  );
};

type VersionMetaProps = {
  version: DatasetVersion;
  isTestSuite: boolean;
  suiteAssertions: string[];
};

const VersionMeta: React.FC<VersionMetaProps> = ({
  version,
  isTestSuite,
  suiteAssertions,
}) => (
  <div className="comet-body-s mb-4 flex flex-col gap-1 text-muted-slate">
    <div>
      {`Created ${formatDate(version.created_at)} by ${version.created_by}`}
      {` · ${version.items_total} items`}
      {` · +${version.items_added} ~${version.items_modified} −${version.items_deleted}`}
      {version.change_description && (
        <span className="text-foreground-secondary">{` · ${version.change_description}`}</span>
      )}
    </div>
    {isTestSuite && (
      <div className="mt-1 flex flex-col gap-1">
        <div className="flex gap-2">
          <span className="w-32 shrink-0">Suite assertions</span>
          {suiteAssertions.length ? (
            <ul className="list-disc pl-4 text-foreground-secondary">
              {suiteAssertions.map((assertion) => (
                <li key={assertion}>{assertion}</li>
              ))}
            </ul>
          ) : (
            <span className="text-foreground-secondary">none</span>
          )}
        </div>
        <div className="flex gap-2">
          <span className="w-32 shrink-0">Execution policy</span>
          <span className="text-foreground-secondary">
            {formatExecutionPolicy(version.execution_policy)}
          </span>
        </div>
      </div>
    )}
  </div>
);

export default VersionRecordsSidebar;
