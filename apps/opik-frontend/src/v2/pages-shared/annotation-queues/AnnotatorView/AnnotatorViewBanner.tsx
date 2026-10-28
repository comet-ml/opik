import React from "react";
import { cn } from "@/lib/utils";
import usePluginsStore from "@/store/PluginsStore";
import { AnnotatorViewEditor } from "./useAnnotatorViewEditor";
import { LogTracesButton, OllieStartButton } from "./AnnotatorViewOllieAction";

type AnnotatorViewBannerProps = {
  editor: AnnotatorViewEditor;
};

const AnnotatorViewBanner: React.FC<AnnotatorViewBannerProps> = ({
  editor,
}) => {
  const BillingLink = usePluginsStore((state) => state.BillingLink);

  const getContent = (): [string, React.ReactNode?] | null => {
    switch (editor.ollieState) {
      case "none":
        return null;
      case "credits":
        return [
          "You need more Ollie credits to edit the Annotator view. A workspace admin can add more.",
          BillingLink && <BillingLink variant="button" />,
        ];
      case "no-data":
        return [
          "Log traces to this project to customize this view with Ollie.",
          <LogTracesButton key="log-traces" />,
        ];
      case "drafting":
        return ["Ollie is drafting a view…"];
      default:
        return editor.isOllieOpen
          ? [
              "Changes from Ollie show up here. Describe what to change in the chat.",
            ]
          : [
              "You can customize this view with Ollie.",
              <OllieStartButton
                key="edit"
                label="Edit with Ollie"
                onClick={() => editor.startOllie()}
              />,
            ];
    }
  };

  const content = getContent();
  if (!content) return null;
  const [text, action] = content;

  return (
    <div className="flex min-h-[38px] items-center justify-between gap-2 rounded-md border border-[color-mix(in_srgb,var(--color-ollie)_40%,transparent)] bg-[color-mix(in_srgb,var(--color-ollie)_5%,transparent)] px-3 py-1.5">
      <span className="comet-body-xs flex items-center gap-2">
        <span
          className={cn(
            "size-1.5 shrink-0 rounded-full bg-[var(--color-ollie)]",
            editor.ollieState === "drafting" && "animate-pulse",
          )}
        />
        {text}
      </span>
      {action}
    </div>
  );
};

export default AnnotatorViewBanner;
