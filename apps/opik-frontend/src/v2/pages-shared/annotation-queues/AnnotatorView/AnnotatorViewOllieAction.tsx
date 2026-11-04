import React from "react";
import { ArrowRight, ArrowUpRight, Loader2 } from "lucide-react";
import { Button } from "@/ui/button";
import OllieOwl from "@/icons/ollie-owl.svg?react";
import OutOfCreditsButton from "@/v2/pages-shared/ollie/OutOfCreditsButton";
import { useOpenQuickStartDialog } from "@/v2/pages-shared/onboarding/QuickstartDialog/QuickstartDialog";

export const OllieDraftingButton: React.FC = () => (
  <Button type="button" variant="outline" size="2xs" disabled>
    <Loader2 className="mr-1 size-3 animate-spin" />
    Ollie is drafting…
  </Button>
);

export const OllieOutOfCreditsButton: React.FC = () => (
  <OutOfCreditsButton
    label="Out of Ollie credits"
    description="You need more credits to customize this view with Ollie. Ollie credits are shared across AI features in Opik. A workspace admin can add more."
  />
);

export const LogTracesButton: React.FC = () => {
  const { open } = useOpenQuickStartDialog();
  return (
    <Button type="button" variant="outline" size="2xs" onClick={open}>
      Log traces
      <ArrowUpRight className="ml-1 size-3" />
    </Button>
  );
};

type OllieStartButtonProps = {
  label: string;
  onClick: () => void;
};

export const OllieStartButton: React.FC<OllieStartButtonProps> = ({
  label,
  onClick,
}) => (
  <Button type="button" variant="outline" size="2xs" onClick={onClick}>
    <OllieOwl className="mr-1 size-3 text-[var(--color-ollie)]" />
    {label}
    <ArrowRight className="ml-1 size-3" />
  </Button>
);
