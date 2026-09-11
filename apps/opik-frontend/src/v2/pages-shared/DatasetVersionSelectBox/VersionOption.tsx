import React from "react";
import { SelectItem } from "@/ui/select";
import { DatasetVersion } from "@/types/datasets";
import ColoredTag from "@/shared/ColoredTag/ColoredTag";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";
import { formatDatasetVersionKey } from "@/utils/datasetVersionStorage";

interface VersionOptionProps {
  version: DatasetVersion;
  datasetId: string;
  isSelected: boolean;
}

const VersionOption: React.FC<VersionOptionProps> = ({
  version,
  datasetId,
  isSelected,
}) => {
  return (
    <SelectItem
      key={version.id}
      wrapperAsChild
      value={formatDatasetVersionKey(datasetId, version.id)}
      className={cn(
        "min-h-8 cursor-pointer px-3 py-1 focus:bg-primary-foreground focus:text-foreground",
        {
          "bg-primary-foreground": isSelected,
        },
      )}
    >
      <div className="flex w-full min-w-0 flex-col">
        <div className="comet-body-s flex items-start gap-2 text-foreground">
          <span className="shrink-0">{version.version_name}</span>
          {version.tags && version.tags.length > 0 && (
            <div className="flex min-w-0 flex-wrap items-center gap-1">
              {version.tags.map((tag) => (
                <ColoredTag key={tag} label={tag} />
              ))}
            </div>
          )}
        </div>
        {version.change_description && (
          <TooltipWrapper content={version.change_description}>
            <span className="comet-body-s w-0 min-w-full truncate text-light-slate">
              {version.change_description}
            </span>
          </TooltipWrapper>
        )}
      </div>
    </SelectItem>
  );
};

export default VersionOption;
