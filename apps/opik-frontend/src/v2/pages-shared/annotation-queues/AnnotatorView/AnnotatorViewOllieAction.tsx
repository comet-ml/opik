import React from "react";
import { ArrowUpRight, Loader2, Sparkles } from "lucide-react";
import { Button } from "@/ui/button";
import OutOfCreditsButton from "@/v2/pages-shared/ollie/OutOfCreditsButton";
import { useOpenQuickStartDialog } from "@/v2/pages-shared/onboarding/QuickstartDialog/QuickstartDialog";
import { AnnotatorViewEditor } from "./useAnnotatorViewEditor";

type AnnotatorViewOllieActionProps = {
  editor: AnnotatorViewEditor;
  onStart: () => void;
};

const AnnotatorViewOllieAction: React.FC<AnnotatorViewOllieActionProps> = ({
  editor,
  onStart,
}) => {
  const { open: openQuickstart } = useOpenQuickStartDialog();

  switch (editor.ollieState) {
    case "none":
      return null;
    case "credits":
      return (
        <OutOfCreditsButton
          label="Out of Ollie credits"
          description="You need more credits to customize this view with Ollie. Ollie credits are shared across AI features in Opik — a workspace admin can add more."
        />
      );
    case "no-data":
      return (
        <Button
          type="button"
          variant="outline"
          size="2xs"
          onClick={openQuickstart}
        >
          Log traces to customize
          <ArrowUpRight className="ml-1 size-3" />
        </Button>
      );
    case "drafting":
      return (
        <Button type="button" variant="outline" size="2xs" disabled>
          <Loader2 className="mr-1 size-3 animate-spin" />
          Ollie is drafting…
        </Button>
      );
    case "ready":
      return (
        <Button type="button" variant="outline" size="2xs" onClick={onStart}>
          <Sparkles className="mr-1 size-3" />
          Edit with Ollie
        </Button>
      );
  }
};

export default AnnotatorViewOllieAction;
