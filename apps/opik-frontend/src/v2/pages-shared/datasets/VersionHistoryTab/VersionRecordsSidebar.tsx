import React, { useCallback, useMemo, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import isEqual from "lodash/isEqual";
import { RotateCcw } from "lucide-react";
import { StringParam, useQueryParam } from "use-query-params";

import { Sheet, SheetContent, SheetTopBar } from "@/ui/sheet";
import { Button } from "@/ui/button";
import { Tag } from "@/ui/tag";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { PortalContainerProvider } from "@/lib/portal-container";
import { extractAssertions } from "@/lib/assertion-converters";
import { formatDate } from "@/lib/date";
import { usePermissions } from "@/contexts/PermissionsContext";
import useDatasetVersionsList from "@/api/datasets/useDatasetVersionsList";
import {
  DatasetItem,
  DatasetItemColumn,
  DatasetVersion,
} from "@/types/datasets";
import { ExecutionPolicy } from "@/types/test-suites";
import { isLatestVersionTag } from "@/constants/datasets";
import { ColumnData, DynamicColumn } from "@/types/shared";
import DatasetItemsTab, {
  EditPanelRenderProps,
  StorageKeysConfig,
} from "@/v2/pages-shared/datasets/DatasetItemsTab/DatasetItemsTab";
import RestoreVersionDialog from "./RestoreVersionDialog";
import VersionItemPanel from "./VersionItemPanel";

export const DVS_QUERY_PREFIX = "dvs_";

type VersionRecordsSidebarProps = {
  datasetId: string;
  datasetName?: string;
  versionHash?: string;
  onClose: () => void;
  isTestSuite: boolean;
  buildColumns: (
    datasetColumns: DatasetItemColumn[],
    dynamicDatasetColumns: DynamicColumn[],
  ) => ColumnData<DatasetItem>[];
  storageKeys: StorageKeysConfig;
  defaultSelectedColumns: string[];
  entityName: string;
  itemName: string;
};

const formatPolicy = (policy?: ExecutionPolicy) =>
  policy
    ? `${policy.pass_threshold} of ${policy.runs_per_item} runs must pass`
    : "—";

const VersionAssertionsCell = (context: CellContext<DatasetItem, unknown>) => {
  const count = extractAssertions(context.row.original.evaluators ?? []).length;
  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-center"
    >
      {count || <span className="text-muted-slate">&mdash;</span>}
    </CellWrapper>
  );
};

const VersionExecutionPolicyCell = (
  context: CellContext<DatasetItem, unknown>,
) => {
  const policy = context.row.original.execution_policy;
  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {policy ? (
        formatPolicy(policy)
      ) : (
        <span className="text-muted-slate">Suite default</span>
      )}
    </CellWrapper>
  );
};

const VERSION_SUITE_CELLS: Record<string, unknown> = {
  assertions: VersionAssertionsCell,
  execution_policy: VersionExecutionPolicyCell,
};

const VersionRecordsSidebar: React.FC<VersionRecordsSidebarProps> = ({
  datasetId,
  datasetName,
  versionHash,
  onClose,
  isTestSuite,
  buildColumns,
  storageKeys,
  defaultSelectedColumns,
  entityName,
  itemName,
}) => {
  const {
    permissions: { canEditDatasets },
  } = usePermissions();
  const [container, setContainer] = useState<HTMLDivElement | null>(null);
  const [restoreOpen, setRestoreOpen] = useState(false);
  const [activeRowId] = useQueryParam(`${DVS_QUERY_PREFIX}row`, StringParam);

  const open = Boolean(versionHash);

  const { data: versionsData } = useDatasetVersionsList(
    { datasetId, page: 1, size: 100 },
    { enabled: open },
  );

  const { version, previousVersion } = useMemo(() => {
    const versions = versionsData?.content ?? [];
    const index = versions.findIndex((v) => v.version_hash === versionHash);
    return {
      version: index >= 0 ? versions[index] : undefined,
      previousVersion: index >= 0 ? versions[index + 1] : undefined,
    };
  }, [versionsData?.content, versionHash]);

  const isLatestVersion = version?.tags?.some(isLatestVersionTag) ?? false;

  const suiteAssertions = useMemo(
    () => extractAssertions(version?.evaluators ?? []),
    [version?.evaluators],
  );
  const suiteSettingsChanged = useMemo(() => {
    if (!isTestSuite || !version || !previousVersion) return false;
    return (
      !isEqual(
        suiteAssertions,
        extractAssertions(previousVersion.evaluators ?? []),
      ) || !isEqual(version.execution_policy, previousVersion.execution_policy)
    );
  }, [isTestSuite, version, previousVersion, suiteAssertions]);

  const buildVersionColumns = useCallback(
    (
      datasetColumns: DatasetItemColumn[],
      dynamicDatasetColumns: DynamicColumn[],
    ) => {
      const columns = buildColumns(datasetColumns, dynamicDatasetColumns);
      if (!isTestSuite) return columns;
      return columns.map((column) =>
        VERSION_SUITE_CELLS[column.id]
          ? { ...column, cell: VERSION_SUITE_CELLS[column.id] as never }
          : column,
      );
    },
    [buildColumns, isTestSuite],
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
        className="flex w-screen flex-col shadow-none sm:max-w-full"
        header={
          <SheetTopBar
            variant="info"
            title={
              <span className="flex items-center gap-2">
                {[version?.version_name, datasetName]
                  .filter(Boolean)
                  .join(" · ")}
                <Tag variant="gray" size="sm">
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
        onEscapeKeyDown={(e) => {
          if (activeRowId) e.preventDefault();
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
                suiteSettingsChanged={suiteSettingsChanged}
              />
            )}
            {open && (
              <DatasetItemsTab
                datasetId={datasetId}
                datasetName={datasetName}
                storageKeys={storageKeys}
                defaultSelectedColumns={defaultSelectedColumns}
                entityName={entityName}
                buildColumns={buildVersionColumns}
                renderEditPanel={renderItemPanel}
                onAddItem={() => {}}
                itemName={itemName}
                versionHash={versionHash}
                queryParamPrefix={DVS_QUERY_PREFIX}
              />
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
  suiteSettingsChanged: boolean;
};

const VersionMeta: React.FC<VersionMetaProps> = ({
  version,
  isTestSuite,
  suiteAssertions,
  suiteSettingsChanged,
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
      <div className="flex flex-wrap items-center gap-x-2">
        <span>
          Suite assertions:{" "}
          <span className="text-foreground-secondary">
            {suiteAssertions.length ? suiteAssertions.join("; ") : "none"}
          </span>
        </span>
        <span>·</span>
        <span>
          Execution policy:{" "}
          <span className="text-foreground-secondary">
            {formatPolicy(version.execution_policy)}
          </span>
        </span>
        {suiteSettingsChanged && (
          <Tag variant="yellow" size="sm">
            Changed in this version
          </Tag>
        )}
      </div>
    )}
  </div>
);

export default VersionRecordsSidebar;
