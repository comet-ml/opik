import copy from "clipboard-copy";
import { Copy } from "lucide-react";
import OllieOwl from "@/icons/ollie-owl.svg?react";
import { toast } from "@/ui/use-toast";
import useAssistantBackend from "@/plugins/comet/useAssistantBackend";
import useAssistantManifest from "@/plugins/comet/useAssistantManifest";

const AssistantDebugInfo = () => {
  const { probeUrl } = useAssistantBackend();
  const meta = useAssistantManifest(probeUrl);

  if (!meta?.version) return null;

  return (
    <div
      className="flex items-center gap-1"
      onClick={() => {
        copy(meta.version);
        toast({ description: "Successfully copied Ollie version" });
      }}
    >
      <span className="comet-body-xs-accented flex items-center gap-1 truncate">
        <OllieOwl className="size-4 text-[var(--color-ollie)]" />
        OLLIE VERSION {meta.version}
      </span>
      <Copy className="size-3 shrink-0" />
    </div>
  );
};

export default AssistantDebugInfo;
