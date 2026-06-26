import React from "react";
import {
  Check,
  CheckCheck,
  FilePen,
  GitCommitVertical,
  Plus,
  Settings2,
  Sparkles,
  X,
} from "lucide-react";

import BackButton from "@/shared/BackButton/BackButton";
import ColoredTag from "@/shared/ColoredTag/ColoredTag";
import DateTag from "@/shared/DateTag/DateTag";
import { RESOURCE_TYPE } from "@/shared/ResourceLink/ResourceLink";
import SingleLineExpandableText from "@/shared/SingleLineExpandableText/SingleLineExpandableText";
import TagListRenderer from "@/shared/TagListRenderer/TagListRenderer";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import UseDatasetDropdown from "@/v2/pages-shared/datasets/UseDatasetDropdown";
import { AssertionsListTooltipContent } from "@/v2/pages-shared/experiments/TestSuiteExperiment/AssertionsListTooltipContent";
import { Button } from "@/ui/button";
import { Tag } from "@/ui/tag";
import {
  Tooltip,
  TooltipContent,
  TooltipPortal,
  TooltipTrigger,
} from "@/ui/tooltip";
import { Dataset } from "@/types/datasets";

interface DatasetItemsPageHeaderProps {
  dataset: Dataset | undefined;
  datasetId: string;
  isTestSuite: boolean;
  entityName: string;
  activeProjectId: string | null | undefined;
  hasDraft: boolean;
  canEditDatasets: boolean;
  effectiveAssertions: string[];
  onAddTag: (tag: string) => void;
  onDeleteTag: (tag: string) => void;
  onDiscardClick: () => void;
  onSaveClick: () => void;
  onSettingsClick: () => void;
  onAddItem: () => void;
  onExpand: () => void;
}

const DatasetItemsPageHeader: React.FunctionComponent<
  DatasetItemsPageHeaderProps
> = ({
  dataset,
  datasetId,
  isTestSuite,
  entityName,
  activeProjectId,
  hasDraft,
  canEditDatasets,
  effectiveAssertions,
  onAddTag,
  onDeleteTag,
  onDiscardClick,
  onSaveClick,
  onSettingsClick,
  onAddItem,
  onExpand,
}) => {
  const datasetTags = dataset?.tags ?? [];
  const showTags = canEditDatasets || datasetTags.length > 0;
  const tagListProps = canEditDatasets
    ? { tags: datasetTags }
    : { tags: [] as string[], immutableTags: datasetTags };

  const latestVersion = dataset?.latest_version;

  return (
    <div className="mb-4">
      <div className="mb-4 flex items-center justify-between gap-2">
        <div className="flex min-w-0 items-center gap-2">
          <BackButton
            to={
              isTestSuite
                ? "/$workspaceName/projects/$projectId/test-suites"
                : "/$workspaceName/projects/$projectId/datasets"
            }
            tooltip={isTestSuite ? "Back to test suites" : "Back to datasets"}
          />
          {hasDraft && (
            <Tag variant="orange" size="md">
              Draft
            </Tag>
          )}
          <h1 className="comet-body-accented truncate break-words">
            {dataset?.name ?? (isTestSuite ? "Test suite" : "Dataset")}
          </h1>
        </div>
        <div className="flex items-center gap-2">
          {hasDraft && (
            <>
              <Button
                variant="outline"
                size="sm"
                onClick={onDiscardClick}
                data-testid="dataset-items-discard-button"
              >
                <X className="mr-1 size-4" />
                Discard changes
              </Button>
              <Button
                variant="default"
                size="sm"
                onClick={onSaveClick}
                data-testid="dataset-items-commit-button"
              >
                <Check className="mr-1 size-4" />
                Save changes
              </Button>
            </>
          )}
          {canEditDatasets && (
            <TooltipWrapper content="Expand with AI">
              <Button
                variant="outline"
                size="icon-sm"
                onClick={onExpand}
                disabled={!dataset}
              >
                <Sparkles />
              </Button>
            </TooltipWrapper>
          )}
          <UseDatasetDropdown
            datasetName={dataset?.name}
            datasetId={datasetId}
            datasetVersionId={latestVersion?.id}
            entityName={entityName}
            projectId={activeProjectId}
            isEmpty={dataset?.dataset_items_count === 0}
            isTestSuite={isTestSuite}
          />
          {isTestSuite && (
            <TooltipWrapper content="Test settings">
              <Button
                variant="outline"
                size="icon-sm"
                onClick={onSettingsClick}
              >
                <Settings2 />
              </Button>
            </TooltipWrapper>
          )}
          {canEditDatasets && (
            <Button
              variant="default"
              size="sm"
              onClick={onAddItem}
              disabled={!dataset}
            >
              <Plus className="mr-1.5 size-3.5" />
              {isTestSuite ? "Test case" : "Record"}
            </Button>
          )}
        </div>
      </div>
      <div className="mb-2 flex gap-1.5 overflow-x-auto">
        {dataset?.created_at && (
          <DateTag
            date={dataset.created_at}
            resource={
              isTestSuite ? RESOURCE_TYPE.testSuite : RESOURCE_TYPE.dataset
            }
          />
        )}
        {latestVersion && (
          <>
            <div
              data-testid="dataset-detail-version-label"
              className="flex h-6 shrink-0 items-center gap-1 rounded-md border border-border bg-primary-foreground pl-1 pr-1.5"
            >
              <span className="flex size-3 shrink-0 items-center justify-center">
                <span className="size-1.5 rounded-full bg-light-slate" />
              </span>
              <span className="comet-body-xs-accented text-foreground">
                {latestVersion.version_name}
              </span>
            </div>
            {latestVersion.tags?.map((tag) => (
              <ColoredTag
                key={tag}
                label={tag}
                size="md"
                variant="gray"
                IconComponent={GitCommitVertical}
              />
            ))}
          </>
        )}
        {isTestSuite && effectiveAssertions.length > 0 && (
          <Tooltip>
            <TooltipTrigger asChild>
              <div
                data-testid="dataset-detail-global-assertions-pill"
                data-count={effectiveAssertions.length}
                className="flex h-6 shrink-0 cursor-pointer items-center gap-1 rounded-md border border-border bg-primary-foreground pl-1 pr-1.5"
                onClick={onSettingsClick}
              >
                <CheckCheck className="size-3 shrink-0 text-muted-slate" />
                <span className="comet-body-xs-accented text-foreground">
                  {effectiveAssertions.length} global assertion
                  {effectiveAssertions.length !== 1 ? "s" : ""}
                </span>
              </div>
            </TooltipTrigger>
            <TooltipPortal>
              <TooltipContent
                side="bottom"
                collisionPadding={16}
                className="max-w-fit p-0"
              >
                <AssertionsListTooltipContent
                  assertions={effectiveAssertions}
                />
              </TooltipContent>
            </TooltipPortal>
          </Tooltip>
        )}
      </div>
      {showTags && (
        <TagListRenderer
          {...tagListProps}
          onAddTag={onAddTag}
          onDeleteTag={onDeleteTag}
          canAdd={canEditDatasets}
          align="start"
          className="[&>:first-child]:-mr-1"
          tagVariant="green"
        />
      )}
      {dataset?.description && (
        <div className="mt-2 flex items-start gap-1">
          <FilePen className="ml-1 mt-[3px] size-3.5 shrink-0 text-muted-slate" />
          <SingleLineExpandableText className="comet-body-s text-foreground">
            {dataset.description}
          </SingleLineExpandableText>
        </div>
      )}
    </div>
  );
};

export default DatasetItemsPageHeader;
