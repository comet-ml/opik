import React, { useState } from "react";
import { FileText, Power } from "lucide-react";
import briefingBulbIcon from "@/icons/briefing-bulb.svg";
import { buildDocsUrl } from "@/lib/utils";
import TurnOnDiagnosticDialog from "@/v2/pages/SignalsPage/TurnOnDiagnosticDialog";

// TODO: point at the dedicated Diagnostics docs page once it ships.
const DIAGNOSTICS_DOCS_URL = buildDocsUrl();

type ActionCardProps = {
  icon: React.ElementType;
  iconClassName: string;
  title: string;
  description: string;
};

const ActionCard: React.FC<
  ActionCardProps &
    ({ as: "button"; onClick: () => void } | { as: "link"; href: string })
> = ({ icon: Icon, iconClassName, title, description, ...rest }) => {
  const content = (
    <>
      <Icon className={`mt-0.5 size-4 shrink-0 ${iconClassName}`} />
      <div className="flex flex-col gap-0.5">
        <span className="comet-body-s-accented text-foreground">{title}</span>
        <span className="comet-body-xs text-light-slate">{description}</span>
      </div>
    </>
  );

  const className =
    "flex w-full items-start gap-3 rounded-lg border border-border p-4 text-left transition-colors hover:border-primary hover:bg-muted/50";

  if (rest.as === "link") {
    return (
      <a
        href={rest.href}
        target="_blank"
        rel="noopener noreferrer"
        className={className}
      >
        {content}
      </a>
    );
  }

  return (
    <button type="button" onClick={rest.onClick} className={className}>
      {content}
    </button>
  );
};

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
    <div className="flex min-h-[60vh] items-center justify-center px-6">
      <div className="flex w-full max-w-4xl items-center justify-between gap-10">
        <div className="flex max-w-xl flex-col gap-4">
          <div className="flex flex-col gap-2">
            <h2 className="comet-title-m text-foreground">
              Catch issues before your users do
            </h2>
            <p className="comet-body-s text-muted-slate">
              Diagnostics scans your traces daily and surfaces non-error issues
              — tool loops, hallucinations, slow retrievals.
            </p>
          </div>

          <div className="flex flex-col gap-3">
            <ActionCard
              as="button"
              onClick={() => setDialogOpen(true)}
              icon={Power}
              iconClassName="text-[var(--color-primary)]"
              title="Turn on diagnostics"
              description="Best with 100+ traces logged over the last 7 days"
            />
            <ActionCard
              as="link"
              href={DIAGNOSTICS_DOCS_URL}
              icon={FileText}
              iconClassName="text-[var(--color-green)]"
              title="View docs"
              description="See how diagnostics works"
            />
          </div>
        </div>

        <img
          src={briefingBulbIcon}
          alt=""
          aria-hidden
          className="hidden w-64 shrink-0 lg:block"
        />
      </div>

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
