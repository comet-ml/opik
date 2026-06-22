import React, { useState } from "react";
import { FileText, ToggleRight } from "lucide-react";
import { buildDocsUrl } from "@/v2/lib/utils";
import TurnOnDiagnosticDialog from "@/v2/pages/SignalsPage/TurnOnDiagnosticDialog";
import RobotLamp from "@/icons/robot-lamp.svg?react";

// TODO: point at the dedicated Diagnostics docs page once it ships.
const DIAGNOSTICS_DOCS_URL = buildDocsUrl();

const CARD_CLASS =
  "flex h-[88px] w-full items-center rounded-lg border border-border bg-background px-4 text-left transition-colors hover:border-primary";

// Inner row keeps the icon aligned with the top (title) text line while the
// outer card centers the whole block vertically.
const CARD_CONTENT_CLASS = "flex items-start gap-2";

type DiagnosticsEmptyStateProps = {
  onRun: () => void;
  isPending: boolean;
};

const DiagnosticsEmptyState: React.FC<DiagnosticsEmptyStateProps> = ({
  onRun,
  isPending,
}) => {
  const [dialogOpen, setDialogOpen] = useState(false);

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
            <span className={CARD_CONTENT_CLASS}>
              <ToggleRight className="mt-1 size-4 shrink-0 text-fuchsia-500" />
              <span className="flex flex-col gap-0.5">
                <span className="comet-body-s-accented text-foreground">
                  Turn on diagnostics
                </span>
                <span className="comet-body-xs text-light-slate">
                  Best with 100+ traces logged over the last 7 days
                </span>
              </span>
            </span>
          </button>

          <a
            href={DIAGNOSTICS_DOCS_URL}
            target="_blank"
            rel="noopener noreferrer"
            className={CARD_CLASS}
          >
            <span className={CARD_CONTENT_CLASS}>
              <FileText className="mt-1 size-4 shrink-0 text-[var(--color-green)]" />
              <span className="flex flex-col gap-0.5">
                <span className="comet-body-s-accented text-foreground">
                  View docs
                </span>
                <span className="comet-body-xs text-light-slate">
                  See how diagnostics works
                </span>
              </span>
            </span>
          </a>
        </div>
      </div>

      <RobotLamp className="hidden size-52 shrink-0 lg:block" />

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
