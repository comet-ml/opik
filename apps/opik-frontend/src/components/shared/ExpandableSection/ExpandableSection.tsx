import React, { ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { Maximize2, Minimize2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { useQueryParam, ArrayParam } from "use-query-params";

export type ExpandableSectionProps = {
  title: string;
  icon?: ReactNode;
  count?: number;
  children: ReactNode;
  contentClassName?: string;
  sectionIdx: number;
  queryParamName: string;
};

const ExpandableSection: React.FC<ExpandableSectionProps> = ({
  title,
  icon,
  count,
  children,
  contentClassName,
  sectionIdx,
  queryParamName,
}) => {
  const [expandedSections = [], setExpandedSections] = useQueryParam(
    queryParamName,
    ArrayParam,
    {
      updateType: "replaceIn",
    },
  );
  const currentSectionIdx = String(sectionIdx);

  const isExpanded = expandedSections?.includes(currentSectionIdx);
  const toggleIsExpanded = () => {
    const filteredExpandedSections =
      expandedSections?.filter((itm) => itm !== currentSectionIdx) || [];
    const newExpandedSections = isExpanded
      ? filteredExpandedSections
      : [...filteredExpandedSections, currentSectionIdx];

    setExpandedSections(newExpandedSections);
  };

  const displayTitle = count ? `${title} (${count})` : title;

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <Button
        className="my-2 flex w-full justify-between hover:bg-muted hover:text-foreground active:text-foreground"
        variant="ghost"
        onClick={toggleIsExpanded}
      >
        <div className="flex items-center gap-2 pr-4">
          {icon} {displayTitle}
        </div>

        {isExpanded ? (
          <Minimize2 className="size-4" />
        ) : (
          <Maximize2 className="size-4" />
        )}
      </Button>

      {isExpanded && (
        <div className={cn("min-h-0 flex-1 overflow-auto", contentClassName)}>
          {children}
        </div>
      )}
    </div>
  );
};

export default ExpandableSection;
