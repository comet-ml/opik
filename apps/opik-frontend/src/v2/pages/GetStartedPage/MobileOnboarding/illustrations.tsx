import React from "react";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";

import welcomeLightUrl from "/images/mobile-onboarding/welcome-light.svg";
import traceLightUrl from "/images/mobile-onboarding/trace-light.svg";
import issuesLightUrl from "/images/mobile-onboarding/issues-light.svg";
import connectLightUrl from "/images/mobile-onboarding/connect-light.svg";

import welcomeDarkUrl from "/images/mobile-onboarding/welcome-dark.svg";
import traceDarkUrl from "/images/mobile-onboarding/trace-dark.svg";
import issuesDarkUrl from "/images/mobile-onboarding/issues-dark.svg";
import connectDarkUrl from "/images/mobile-onboarding/connect-dark.svg";

const URLS = {
  [THEME_MODE.LIGHT]: {
    welcome: welcomeLightUrl,
    trace: traceLightUrl,
    issues: issuesLightUrl,
    connect: connectLightUrl,
  },
  [THEME_MODE.DARK]: {
    welcome: welcomeDarkUrl,
    trace: traceDarkUrl,
    issues: issuesDarkUrl,
    connect: connectDarkUrl,
  },
};

export const allIllustrationUrls = [
  welcomeLightUrl,
  traceLightUrl,
  issuesLightUrl,
  connectLightUrl,
  welcomeDarkUrl,
  traceDarkUrl,
  issuesDarkUrl,
  connectDarkUrl,
];

const useIllustrationUrls = () => {
  const { themeMode } = useTheme();
  return URLS[themeMode];
};

interface IllustrationProps {
  src: string;
  alt: string;
}

const Illustration: React.FC<IllustrationProps> = ({ src, alt }) => (
  <img src={src} alt={alt} className="h-16 self-start" />
);

export const WelcomeIllustration: React.FC = () => {
  const urls = useIllustrationUrls();
  return <Illustration src={urls.welcome} alt="Welcome" />;
};

export const TraceIllustration: React.FC = () => {
  const urls = useIllustrationUrls();
  return <Illustration src={urls.trace} alt="Trace" />;
};

export const IssuesIllustration: React.FC = () => {
  const urls = useIllustrationUrls();
  return <Illustration src={urls.issues} alt="Issues" />;
};

export const ConnectIllustration: React.FC = () => {
  const urls = useIllustrationUrls();
  return <Illustration src={urls.connect} alt="Connect" />;
};
