import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/components/ui/button";
import { DetailsActionSectionValue, DetailsActionSection } from "./types";
import { MessageSquareMore, PenLine, Sparkles } from "lucide-react";
import { cn } from "@/lib/utils";

export enum ButtonLayoutSize {
  Large = "lg",
  Small = "sm",
}

const isLargeLayout = (layoutSize: ButtonLayoutSize) =>
  layoutSize === ButtonLayoutSize.Large;
const formatCounter = (
  layoutSize: ButtonLayoutSize,
  count?: number | string,
) => {
  if (!count) return;
  return isLargeLayout(layoutSize) ? `(${count})` : String(count);
};

const configMap = {
  [DetailsActionSection.Annotations]: {
    icon: <PenLine className="size-3.5" />,
    tooltip: "Feedback scores",
  },
  [DetailsActionSection.Comments]: {
    icon: <MessageSquareMore className="size-3.5" />,
    tooltip: "Comments",
  },
  [DetailsActionSection.AIAssistants]: {
    icon: <Sparkles className="size-3.5" />,
    tooltip: "AI-powered trace analysis",
  },
};

type DetailsActionSectionToggleProps = {
  activeSection: DetailsActionSectionValue | null;
  setActiveSection: (v: DetailsActionSectionValue) => void;
  layoutSize: ButtonLayoutSize;
  count?: number | string;
  type: DetailsActionSectionValue;
  disabled?: boolean;
  tooltipContent?: string;
};
const DetailsActionSectionToggle: React.FC<DetailsActionSectionToggleProps> = ({
  activeSection,
  setActiveSection,
  layoutSize,
  count,
  type,
  disabled,
  tooltipContent,
}) => {
  const showFullActionLabel = isLargeLayout(layoutSize);

  return (
    <TooltipWrapper content={tooltipContent || configMap[type].tooltip}>
      <div>
        <Button
          variant="outline"
          size="sm"
          onClick={() => setActiveSection(type)}
          className={cn(
            "gap-1",
            activeSection === type && "bg-primary-100 hover:bg-primary-100",
          )}
          disabled={disabled}
        >
          {configMap[type].icon}
          {showFullActionLabel && (
            <div className="pl-1">{configMap[type].tooltip}</div>
          )}
          {Boolean(count) && <div>{formatCounter(layoutSize, count)}</div>}
        </Button>
      </div>
    </TooltipWrapper>
  );
};

export default DetailsActionSectionToggle;
