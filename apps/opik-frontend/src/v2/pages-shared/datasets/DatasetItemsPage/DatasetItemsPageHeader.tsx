import React from "react";
import {
  Check,
  CheckCheck,
  GitCommitVertical,
  Settings2,
  X,
} from "lucide-react";

import ColoredTag from "@/shared/ColoredTag/ColoredTag";
import DateTag from "@/shared/DateTag/DateTag";
import { RESOURCE_TYPE } from "@/shared/ResourceLink/ResourceLink";
import TagListRenderer from "@/shared/TagListRenderer/TagListRenderer";
import UseDatasetDropdown from "@/v2/pages-shared/datasets/UseDatasetDropdown";
import { AssertionsListTooltipContent } from "@/v2/pages-shared/experiments/TestSuiteExperiment/AssertionsListTooltipContent";
import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
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
        <div className="flex items-center gap-2">
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
              <Button variant="outline" size="sm" onClick={onDiscardClick}>
                <X className="mr-1 size-4" />
                Discard changes
              </Button>
              <Button variant="default" size="sm" onClick={onSaveClick}>
                <Check className="mr-1 size-4" />
                Save changes
              </Button>
            </>
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
            <Button
              variant="outline"
              size="sm"
              className="gap-1.5"
              onClick={onSettingsClick}
            >
              <Settings2 className="size-3.5 shrink-0" />
              Test settings
            </Button>
          )}
        </div>
      </div>
      {dataset?.description && (
        <div className="-mt-3 mb-4 text-muted-slate">{dataset.description}</div>
      )}
      <div className="flex gap-2 overflow-x-auto">
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
            <Tag
              size="md"
              variant="transparent"
              className="flex shrink-0 items-center gap-1"
            >
              <GitCommitVertical className="size-3 text-green-500" />
              {latestVersion.version_name}
            </Tag>
            {latestVersion.tags?.map((tag) => (
              <ColoredTag
                key={tag}
                label={tag}
                size="md"
                IconComponent={GitCommitVertical}
              />
            ))}
          </>
        )}
        {isTestSuite && effectiveAssertions.length > 0 && (
          <Tooltip>
            <TooltipTrigger asChild>
              <div
                className="flex shrink-0 cursor-pointer items-center gap-1 rounded bg-thread-active px-1.5 py-0.5"
                onClick={onSettingsClick}
              >
                <CheckCheck className="size-3 text-muted-foreground" />
                <span className="comet-body-s-accented text-muted-foreground">
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
        {showTags && (
          <>
            <Separator orientation="vertical" className="ml-1.5 mt-1 h-4" />
            <TagListRenderer
              {...tagListProps}
              onAddTag={onAddTag}
              onDeleteTag={onDeleteTag}
              canAdd={canEditDatasets}
              align="start"
              className="min-h-0 w-auto"
            />
          </>
        )}
      </div>
    </div>
  );
};

export default DatasetItemsPageHeader;
