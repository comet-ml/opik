import welcomeBannerUrl from "/images/welcome-banner.png";
import { Button } from "@/components/ui/button";
import { ChevronRight, X } from "lucide-react";
import useAppStore from "@/store/AppStore";
import React from "react";

type WelcomeBannerProps = {
  setOpen: (open: boolean) => void;
};

const WelcomeBanner: React.FC<WelcomeBannerProps> = ({ setOpen }) => {
  const { setQuickstartOpened } = useAppStore();

  return (
    <div
      className="relative mb-4 flex min-h-[200px] items-center rounded-md bg-cover bg-center bg-no-repeat p-8"
      style={{
        backgroundImage: `url(${welcomeBannerUrl}), linear-gradient(180deg, #060A28 0%, #604FFF 100%)`,
      }}
    >
      <Button
        variant="minimal"
        size="icon-sm"
        onClick={() => setOpen(false)}
        className="absolute right-2 top-2 !p-0"
      >
        <X />
      </Button>
      <div className="p-7">
        <div className="comet-title-xl text-white">Welcome to Opik 👋</div>
        <div className="comet-body mt-4 text-white/80">
          Opik helps you build safer, more reliable AI systems through advanced
          tracing, experiment management, evaluation, and real-time monitoring
        </div>
        <Button
          variant="secondary"
          className="mt-6"
          onClick={() => setQuickstartOpened(true)}
        >
          Get started <ChevronRight className="ml-2 size-4 shrink-0" />
        </Button>
      </div>
    </div>
  );
};

export default WelcomeBanner;
