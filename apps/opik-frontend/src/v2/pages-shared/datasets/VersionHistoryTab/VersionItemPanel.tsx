import React, { useMemo } from "react";

import ResizableSidePanel from "@/shared/ResizableSidePanel/ResizableSidePanel";
import ResizableSidePanelTopBar from "@/shared/ResizableSidePanel/ResizableSidePanelTopBar";
import ResizableSidePanelArrowNavigation from "@/shared/ResizableSidePanel/ResizableSidePanelArrowNavigation";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import TagListRenderer from "@/shared/TagListRenderer/TagListRenderer";
import ImagesListWrapper from "@/shared/attachments/ImagesListWrapper/ImagesListWrapper";
import { processInputData } from "@/lib/images";
import { extractAssertions } from "@/lib/assertion-converters";
import { DatasetVersion } from "@/types/datasets";
import { EditPanelRenderProps } from "@/v2/pages-shared/datasets/DatasetItemsTab/DatasetItemsTab";
import DatasetItemEditorForm from "@/v2/pages-shared/datasets/DatasetItemEditor/DatasetItemEditorForm";
import { DatasetField } from "@/v2/pages-shared/datasets/DatasetItemEditor/hooks/useDatasetItemData";
import { getFieldType } from "@/v2/pages-shared/datasets/DatasetItemEditor/hooks/useDatasetItemFormHelpers";
import { useDatasetItemNavigation } from "@/v2/pages-shared/datasets/DatasetItemEditor/hooks/useDatasetItemNavigation";

type VersionItemPanelProps = EditPanelRenderProps & {
  version: DatasetVersion;
  isTestSuite: boolean;
  itemLabel: string;
  container: HTMLElement | null;
};

const truncateId = (id: string) =>
  id.length <= 12 ? id : `${id.slice(0, 4)}...${id.slice(-4)}`;

const noop = () => {};

const Section: React.FC<{ title: string; children: React.ReactNode }> = ({
  title,
  children,
}) => (
  <div className="border-b px-6 py-4">
    <div className="comet-body-s-accented mb-2">{title}</div>
    {children}
  </div>
);

const VersionItemPanel: React.FC<VersionItemPanelProps> = ({
  datasetItemId,
  columns,
  onClose,
  isOpen,
  rows,
  setActiveRowId,
  version,
  isTestSuite,
  itemLabel,
  container,
}) => {
  const item = useMemo(
    () => rows.find((row) => row.id === datasetItemId),
    [rows, datasetItemId],
  );

  const { horizontalNavigation } = useDatasetItemNavigation({
    activeRowId: datasetItemId,
    rows,
    setActiveRowId,
  });

  const fields = useMemo<DatasetField[]>(() => {
    const data = (item?.data ?? {}) as Record<string, unknown>;
    return columns
      .filter((column) => data[column.name] !== undefined)
      .map((column) => ({
        key: column.name,
        ...getFieldType(data[column.name]),
      }));
  }, [item, columns]);

  const { media } = useMemo(() => processInputData(item?.data), [item?.data]);

  const assertions = useMemo(
    () => extractAssertions(item?.evaluators ?? []),
    [item?.evaluators],
  );
  const policy = item?.execution_policy ?? version.execution_policy;

  return (
    <ResizableSidePanel
      panelId="dataset-version-item"
      open={isOpen && Boolean(item)}
      container={container}
      header={
        <ResizableSidePanelTopBar
          variant="info"
          title={
            <TooltipWrapper content={datasetItemId}>
              <span>
                {itemLabel}{" "}
                <span className="comet-body-s text-muted-slate">
                  {truncateId(datasetItemId)}
                </span>
              </span>
            </TooltipWrapper>
          }
          onClose={onClose}
        >
          <ResizableSidePanelArrowNavigation
            horizontalNavigation={horizontalNavigation}
          />
        </ResizableSidePanelTopBar>
      }
      onClose={onClose}
      horizontalNavigation={horizontalNavigation}
    >
      {item && (
        <div className="relative size-full overflow-y-auto">
          {Boolean(item.tags?.length) && (
            <div className="border-b px-6 py-4">
              <TagListRenderer
                tags={item.tags ?? []}
                onAddTag={noop}
                onDeleteTag={noop}
                readOnly
                size="sm"
                align="start"
              />
            </div>
          )}
          {isTestSuite && (
            <Section title="Description">
              <div className="comet-body-s whitespace-pre-wrap text-foreground-secondary">
                {item.description || "—"}
              </div>
            </Section>
          )}
          {media.length > 0 && (
            <Section title="Media">
              <ImagesListWrapper media={media} />
            </Section>
          )}
          <div className="px-6 pt-4">
            <div className="comet-body-s-accented">Data</div>
            <DatasetItemEditorForm
              key={datasetItemId}
              formId={`version-item-${datasetItemId}`}
              fields={fields}
              readOnly
            />
          </div>
          {isTestSuite && (
            <>
              <Section title="Assertions">
                {assertions.length ? (
                  <ul className="comet-body-s list-disc space-y-1 pl-5 text-foreground-secondary">
                    {assertions.map((assertion) => (
                      <li key={assertion}>{assertion}</li>
                    ))}
                  </ul>
                ) : (
                  <div className="comet-body-s text-muted-slate">
                    Only suite assertions
                  </div>
                )}
              </Section>
              <Section title="Execution policy">
                <div className="comet-body-s text-foreground-secondary">
                  {policy
                    ? `${policy.pass_threshold} of ${policy.runs_per_item} runs must pass`
                    : "—"}
                  {!item.execution_policy && (
                    <span className="text-muted-slate"> (suite default)</span>
                  )}
                </div>
              </Section>
            </>
          )}
        </div>
      )}
    </ResizableSidePanel>
  );
};

export default VersionItemPanel;
