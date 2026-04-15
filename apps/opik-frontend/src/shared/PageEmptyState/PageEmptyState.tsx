import React from "react";
import { ExternalLink } from "lucide-react";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";
import { Button } from "@/ui/button";

type PageEmptyStateProps = {
  lightImageUrl: string;
  darkImageUrl: string;
  title: string;
  description: string;
  primaryActionLabel?: string;
  onPrimaryAction?: () => void;
  docsUrl?: string;
  docsLabel?: string;
};

const PageEmptyState: React.FC<PageEmptyStateProps> = ({
  lightImageUrl,
  darkImageUrl,
  title,
  description,
  primaryActionLabel,
  onPrimaryAction,
  docsUrl,
  docsLabel = "View docs",
}) => {
  const { themeMode } = useTheme();
  const imageUrl = themeMode === THEME_MODE.DARK ? darkImageUrl : lightImageUrl;

  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-7 py-10">
      <img src={imageUrl} alt={title} />
      <div className="flex flex-col items-center gap-1.5">
        <h2 className="comet-title-s text-foreground">{title}</h2>
        <p className="comet-body-s max-w-[570px] whitespace-pre-line text-center text-muted-slate">
          {description}
        </p>
      </div>
      <div className="flex items-center gap-3">
        {primaryActionLabel && onPrimaryAction && (
          <Button size="sm" onClick={onPrimaryAction}>
            {primaryActionLabel}
          </Button>
        )}
        {docsUrl && (
          <Button variant="secondary" size="sm" asChild>
            <a href={docsUrl} target="_blank" rel="noreferrer">
              {docsLabel}
              <ExternalLink className="ml-1.5 size-3.5" />
            </a>
          </Button>
        )}
      </div>
    </div>
  );
};

export default PageEmptyState;
