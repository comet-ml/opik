import React, { useState } from "react";
import { ExternalLink, Radar } from "lucide-react";
import { Button } from "@/ui/button";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";
import { buildDocsUrl } from "@/v2/lib/utils";
import TurnOnDiagnosticDialog from "@/v2/pages/SignalsPage/TurnOnDiagnosticDialog";
import emptyDiagnosticsLightUrl from "/images/empty-prompt-library-light.svg";
import emptyDiagnosticsDarkUrl from "/images/empty-prompt-library-dark.svg";

// TODO: point at the dedicated Diagnostics docs page once it ships.
const DIAGNOSTICS_DOCS_URL = buildDocsUrl();

type DiagnosticsEmptyStateProps = {
  onRun: () => void;
  isPending: boolean;
};

const DiagnosticsEmptyState: React.FC<DiagnosticsEmptyStateProps> = ({
  onRun,
  isPending,
}) => {
  const [dialogOpen, setDialogOpen] = useState(false);
  const { themeMode } = useTheme();
  const emptyImageUrl =
    themeMode === THEME_MODE.DARK
      ? emptyDiagnosticsDarkUrl
      : emptyDiagnosticsLightUrl;

  const handleConfirm = () => {
    onRun();
    setDialogOpen(false);
  };

  return (
    <div className="flex flex-1 items-center justify-center gap-12 px-8 py-10">
      <div className="flex w-full max-w-md flex-col gap-6">
        <div className="flex flex-col gap-2">
          <h2 className="comet-title-s text-foreground">
            Catch issues before your users do
          </h2>
          <p className="comet-body-s text-muted-slate">
            Diagnostics scans your traces daily and surfaces non-error issues —
            tool loops, hallucinations, slow retrievals.
          </p>
        </div>

        <div className="flex flex-col gap-2">
          <button
            type="button"
            onClick={() => setDialogOpen(true)}
            className="group flex w-full flex-col gap-1 rounded-md border border-border bg-background px-4 py-3 text-left transition-colors hover:border-primary"
          >
            <span className="flex items-center gap-2">
              <Radar className="size-4 shrink-0 text-chart-purple" />
              <span className="comet-body-s-accented text-foreground">
                Turn on diagnostics
              </span>
            </span>
            <span className="comet-body-xs text-muted-slate">
              Best with 100+ traces logged over the last 7 days
            </span>
          </button>
        </div>

        <div>
          <Button variant="outline" size="sm" asChild>
            <a href={DIAGNOSTICS_DOCS_URL} target="_blank" rel="noreferrer">
              View docs
              <ExternalLink className="ml-1.5 size-3.5" />
            </a>
          </Button>
        </div>
      </div>

      <img
        src={emptyImageUrl}
        alt="Catch issues before your users do"
        className="hidden max-w-sm shrink-0 lg:block"
      />

      <TurnOnDiagnosticDialog
        open={dialogOpen}
        setOpen={setDialogOpen}
        onConfirm={handleConfirm}
        isPending={isPending}
      />
    </div>
  );
};

export default DiagnosticsEmptyState;
