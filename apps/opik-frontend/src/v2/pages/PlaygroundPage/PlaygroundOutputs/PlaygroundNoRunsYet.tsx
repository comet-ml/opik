import { AlignLeft } from "lucide-react";

interface PlaygroundNoRunsYetProps {
  color: string;
}

const PlaygroundNoRunsYet = ({ color }: PlaygroundNoRunsYetProps) => (
  <div className="flex size-full flex-col items-center justify-center gap-2">
    <AlignLeft className="size-5" style={{ color }} />
    <p className="comet-body-s-accented">No runs yet</p>
    <p className="comet-body-s text-light-slate">
      Run a prompt to see results.
    </p>
  </div>
);

export default PlaygroundNoRunsYet;
