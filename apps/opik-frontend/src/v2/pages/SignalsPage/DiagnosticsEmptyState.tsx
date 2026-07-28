import React, { useState } from "react";
import { FileText, ToggleRight } from "lucide-react";
import { buildDocsUrl } from "@/v2/lib/utils";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";
import TurnOnDiagnosticDialog from "@/v2/pages/SignalsPage/TurnOnDiagnosticDialog";
import RobotLamp from "@/icons/robot-lamp.svg?react";
import RobotLampDark from "@/icons/robot-lamp-dark.svg?react";

const DIAGNOSTICS_DOCS_URL = buildDocsUrl("/tracing/diagnostics");

const CARD_CLASS =
  "flex h-[88px] w-full items-center rounded-lg border border-border bg-background px-4 text-left transition-colors hover:border-primary hover:bg-toggle-outline-active";

const CARD_CONTENT_CLASS = "flex items-start gap-2";

type DiagnosticsEmptyStateProps = {
  onRun: () => void;
  isPending: boolean;
  canConfigure: boolean;
};

const DiagnosticsEmptyState: React.FC<DiagnosticsEmptyStateProps> = ({
  onRun,
  isPending,
  canConfigure,
}) => {
  const [dialogOpen, setDialogOpen] = useState(false);
  const { themeMode } = useTheme();
  const Lamp = themeMode === THEME_MODE.DARK ? RobotLampDark : RobotLamp;

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
            We&apos;ll scan your traces every day and flag anything worth your
            attention — tool loops, hallucinations, slow retrievals.
          </p>
        </div>

        <div className="flex flex-col gap-3">
          {canConfigure && (
            <button
              type="button"
              onClick={() => setDialogOpen(true)}
              className={CARD_CLASS}
            >
              <span className={CARD_CONTENT_CLASS}>
                <ToggleRight className="mt-1 size-4 shrink-0 text-[var(--color-fuchsia)]" />
                <span className="flex flex-col gap-0.5">
                  <span className="comet-body-s-accented text-foreground">
                    Turn on diagnostics
                  </span>
                  <span className="comet-body-xs text-light-slate">
                    Works best with 100+ traces from the past 7 days.
                  </span>
                </span>
              </span>
            </button>
          )}

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

      <Lamp className="hidden size-52 shrink-0 lg:block" />

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
