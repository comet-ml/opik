import welcomeBannerUrl from "/images/welcome-banner.png";
import { Button } from "@/components/ui/button";
import { ChevronRight, X } from "lucide-react";
import React from "react";
import { useTranslation } from "react-i18next";
import { useOpenQuickStartDialog } from "@/components/pages-shared/onboarding/QuickstartDialog/QuickstartDialog";

type WelcomeBannerProps = {
  setOpen: (open: boolean) => void;
};

const WelcomeBanner: React.FC<WelcomeBannerProps> = ({ setOpen }) => {
  const { t } = useTranslation();
  const { open: openQuickstart } = useOpenQuickStartDialog();

  return (
    <div
      className="relative mb-4 flex min-h-[200px] items-center rounded-md bg-cover bg-center bg-no-repeat p-8"
      style={{
        backgroundImage: `url(${welcomeBannerUrl}), linear-gradient(180deg, var(--banner-gradient-start) 0%, var(--banner-gradient-end) 100%)`,
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
        <div className="comet-title-xl text-white">
          {t("home.welcomeBanner.title")}
        </div>
        <div className="comet-body text-white/80 mt-4">
          {t("home.welcomeBanner.subtitle")}
        </div>
        <Button variant="secondary" className="mt-6" onClick={openQuickstart}>
          {t("home.sections.getStarted")}{" "}
          <ChevronRight className="ml-2 size-4 shrink-0" />
        </Button>
      </div>
    </div>
  );
};

export default WelcomeBanner;
