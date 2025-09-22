import { Button } from "@/components/ui/button";
import { SquareArrowOutUpRight } from "lucide-react";
import colabLogo from "/images/colab-logo.png";

export type GoogleColabCardCoreProps = {
  link: string;
};
const GoogleColabCardCore: React.FC<GoogleColabCardCoreProps> = ({ link }) => {
  return (
    <div className="flex flex-1 flex-col justify-between gap-4 rounded-md border bg-background p-6">
      <div className="comet-title-xs text-foreground-secondary">
        Full example
      </div>
      <div className="gap-3">
        <div className="comet-body-s mb-4 text-muted-slate">
          Try this end to end example in Google Colab:
        </div>
        <Button variant="outline" asChild className="w-full justify-between">
          <a href={link} target="_blank" rel="noreferrer">
            <div className="flex items-center gap-1">
              Open in Colab
              <img src={colabLogo} alt="colab logo" className="h-[27px] w-8" />
            </div>

            <SquareArrowOutUpRight className="ml-2 size-4 shrink-0" />
          </a>
        </Button>
      </div>
    </div>
  );
};

export default GoogleColabCardCore;
