import React from "react";
import { Check, GitCommitVertical } from "lucide-react";
import { SelectItem } from "@/components/ui/select";
import { DatasetVersion } from "@/types/datasets";
import ColoredTag from "../ColoredTag/ColoredTag";
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
      value={formatDatasetVersionKey(datasetId, version.id)}
      withoutCheck
      className={cn(
        "flex h-auto min-h-10 flex-col cursor-pointer justify-center py-2 pl-12 min-w-40 focus:bg-primary-foreground focus:text-foreground",
        {
          "bg-primary-foreground/50": isSelected,
          "bg-primary-foreground": isSelected,
        },
      )}
    >
      {isSelected && (
        <Check className="absolute left-5 top-3 size-4 text-muted-slate" />
      )}
      <div className="comet-body-s max-w-[220px] text-light-slate">
        <GitCommitVertical className="inline-block size-4 text-muted-slate" />
        <span className="comet-body-s pr-2 text-foreground">
          {version.version_name}
        </span>
        {version.change_description}
      </div>
      {version.tags && version.tags.length > 0 && (
        <div className="mt-1 flex flex-wrap gap-1">
          {version.tags.map((tag) => (
            <ColoredTag key={tag} label={tag} />
          ))}
        </div>
      )}
    </SelectItem>
  );
};

export default VersionOption;
