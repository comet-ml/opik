import React from "react";
import { GitCommitVertical, Layers } from "lucide-react";
import { Tag } from "@/ui/tag";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

type ConfigurationVersionTagProps = {
  version: number | string;
  maskId?: string;
  variant?: "default" | "white";
};

const ConfigurationVersionTag: React.FC<ConfigurationVersionTagProps> = ({
  version,
  maskId,
  variant = "default",
}) => {
  const hasMask = Boolean(maskId);

  const getTagVariant = () => {
    if (variant === "white") return "white";
    return hasMask ? "purple" : "gray";
  };

  const tag = (
    <Tag
      className="inline-flex items-center gap-1"
      variant={getTagVariant()}
      size="md"
    >
      {hasMask ? (
        <Layers className="size-3.5 shrink-0" />
      ) : (
        <GitCommitVertical className="size-3.5 shrink-0" />
      )}
      {version}
    </Tag>
  );

  if (hasMask) {
    return (
      <TooltipWrapper content="This configuration has been modified by the opik connect">
        {tag}
      </TooltipWrapper>
    );
  }

  return tag;
};

export default ConfigurationVersionTag;
