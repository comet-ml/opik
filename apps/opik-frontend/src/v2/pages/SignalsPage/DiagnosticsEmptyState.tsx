import React, { useState } from "react";
import { Eye, FileText } from "lucide-react";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";
import { buildDocsUrl } from "@/v2/lib/utils";
import TurnOnDiagnosticDialog from "@/v2/pages/SignalsPage/TurnOnDiagnosticDialog";
import emptyDiagnosticsLightUrl from "/images/empty-diagnostics-light.svg";
import emptyDiagnosticsDarkUrl from "/images/empty-diagnostics-dark.svg";

// TODO: point at the dedicated Diagnostics docs page once it ships.
const DIAGNOSTICS_DOCS_URL = buildDocsUrl();

const CARD_CLASS =
  "flex w-full items-start gap-3 rounded-lg border border-border bg-background px-4 py-3 text-left transition-colors hover:border-primary";

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
    <div className="flex min-h-[70vh] items-center justify-center gap-16 px-8 py-10">
      <div className="flex w-full max-w-xl flex-col gap-6">
        <div className="flex flex-col gap-2">
          <h2 className="comet-title-s text-foreground">
            Catch issues before your users do
          </h2>
          <p className="comet-body-s text-muted-slate">
            Diagnostics scans your traces daily and surfaces non-error issues —
            tool loops, hallucinations, slow retrievals.
          </p>
        </div>

        <div className="flex flex-col gap-3">
          <button
            type="button"
            onClick={() => setDialogOpen(true)}
            className={CARD_CLASS}
          >
            <Eye className="mt-0.5 size-4 shrink-0 text-fuchsia-500" />
            <div className="flex flex-col gap-0.5">
              <span className="comet-body-s-accented text-foreground">
                Turn on diagnostics
              </span>
              <span className="comet-body-xs text-light-slate">
                Best with 100+ traces logged over the last 7 days
              </span>
            </div>
          </button>

          <a
            href={DIAGNOSTICS_DOCS_URL}
            target="_blank"
            rel="noopener noreferrer"
            className={CARD_CLASS}
          >
            <FileText className="mt-0.5 size-4 shrink-0 text-[var(--color-green)]" />
            <div className="flex flex-col gap-0.5">
              <span className="comet-body-s-accented text-foreground">
                View docs
              </span>
              <span className="comet-body-xs text-light-slate">
                See how diagnostics works
              </span>
            </div>
          </a>
        </div>
      </div>

      <img
        src={emptyImageUrl}
        alt="Catch issues before your users do"
        className="hidden max-w-xs shrink-0 lg:block"
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
