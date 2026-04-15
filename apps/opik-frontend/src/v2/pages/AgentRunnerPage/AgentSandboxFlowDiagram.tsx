import React from "react";
import {
  BrainCircuit,
  Bot,
  FileTerminal,
  GitBranch,
  Hash,
  MessageSquareMore,
  Play,
  Settings2,
  Timer,
  Wrench,
} from "lucide-react";

import OllieOwl from "@/icons/ollie-owl.svg?react";
import CurveRight from "@/icons/sandbox-curve-right.svg?react";
import CurveLeft from "@/icons/sandbox-curve-left.svg?react";
import opikLogoUrl from "/images/opik-logo.png";
import opikLogoInvertedUrl from "/images/opik-logo-inverted.png";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";

const Tag: React.FC<{
  icon: React.ReactNode;
  label: string;
}> = ({ icon, label }) => (
  <span className="inline-flex items-center gap-1">
    <span className="flex size-2.5 items-center text-light-slate">{icon}</span>
    <span className="text-[10px] text-foreground">{label}</span>
  </span>
);

const VerticalArrow = () => (
  <svg width="6" height="26" viewBox="0 0 6 26" fill="none" className="mx-auto">
    <path
      d="M3.22857 0.11672C3.07235 -0.0394918 2.81908 -0.0394918 2.66287 0.11672L0.117244 2.66235C-0.0389683 2.81856 -0.0389683 3.07183 0.117244 3.22804C0.273456 3.38426 0.526727 3.38426 0.682939 3.22804L2.94572 0.965263L5.2085 3.22804C5.36471 3.38426 5.61798 3.38426 5.77419 3.22804C5.93041 3.07183 5.93041 2.81856 5.77419 2.66235L3.22857 0.11672ZM2.94572 26L3.34573 26L3.34573 0.399567L2.94572 0.399567L2.54571 0.399568L2.54571 26L2.94572 26Z"
      fill="#5155F5"
    />
  </svg>
);

const AgentSandboxFlowDiagram: React.FC = () => {
  const { themeMode } = useTheme();
  const logoUrl =
    themeMode === THEME_MODE.DARK ? opikLogoInvertedUrl : opikLogoUrl;

  return (
    <div className="flex items-center">
      {/* Opik card */}
      <div
        className="flex w-[320px] shrink-0 flex-col rounded border border-primary bg-background p-2.5"
        style={{
          backgroundImage: `repeating-linear-gradient(
          135deg,
          hsl(var(--primary-100)) 0px, hsl(var(--primary-100)) 1px,
          transparent 1px, transparent 5px,
          hsl(var(--primary-100)) 5px, hsl(var(--primary-100)) 6px,
          transparent 6px, transparent 10px,
          hsl(var(--primary-100)) 10px, hsl(var(--primary-100)) 12px,
          transparent 12px, transparent 16px,
          hsl(var(--primary-100)) 16px, hsl(var(--primary-100)) 18px,
          transparent 18px, transparent 22px
        )`,
        }}
      >
        <img src={logoUrl} alt="Opik" className="mb-2 h-3 w-auto self-start" />

        {/* Configure + Input row (equal height) */}
        <div className="flex gap-1.5">
          <div className="flex flex-1 flex-col rounded border border-border bg-background px-2 py-1">
            <span className="mb-1 text-[10px] font-medium">Configure</span>
            <div className="flex flex-1 flex-col">
              <Tag
                icon={<FileTerminal className="size-2.5" />}
                label="Prompts"
              />
              <Tag icon={<Wrench className="size-2.5" />} label="Tools" />
              <Tag icon={<BrainCircuit className="size-2.5" />} label="Model" />
              <Tag icon={<Settings2 className="size-2.5" />} label="Params" />
            </div>
          </div>

          <div className="flex flex-1 flex-col rounded border border-border bg-background px-2 py-1">
            <span className="mb-1 text-[10px] font-medium">Input</span>
            <div className="flex flex-1 flex-col gap-1">
              <div className="flex-1 rounded border bg-primary-foreground px-2 py-1">
                <span className="font-mono text-[10px] leading-snug text-foreground">
                  &ldquo;What can you
                  <br />
                  do?&rdquo;
                </span>
              </div>
              <div className="flex items-center justify-center gap-1 rounded border px-2 py-0.5">
                <Play className="size-2.5" />
                <span className="text-[10px] font-medium">Run</span>
              </div>
            </div>
          </div>
        </div>

        <VerticalArrow />

        {/* Improve with Ollie */}
        <div className="rounded border border-primary bg-primary-foreground px-2 py-1.5">
          <div className="flex items-center gap-1.5">
            <OllieOwl className="size-3 shrink-0" />
            <span className="text-[11px] font-medium">Improve with Ollie</span>
          </div>
          <p className="mt-0.5 text-[10px] text-muted-slate">
            Analyze traces, suggest prompt tweaks
          </p>
        </div>

        <VerticalArrow />

        {/* Inspect */}
        <div className="rounded border border-border bg-background px-2 py-1.5">
          <span className="mb-1 block text-[10px] font-medium">Inspect</span>
          <div className="flex flex-wrap gap-x-3 gap-y-0.5">
            <Tag
              icon={<MessageSquareMore className="size-2.5" />}
              label="Response"
            />
            <Tag icon={<GitBranch className="size-2.5" />} label="Trajectory" />
            <Tag icon={<Timer className="size-2.5" />} label="Latency" />
            <Tag icon={<Hash className="size-2.5" />} label="Tokens" />
          </div>
        </div>
      </div>

      {/* Curved arrows + Your agent */}
      <div className="relative h-[270px] w-[170px] shrink-0">
        {/* Top curve: ends closer to box vertical center */}
        <CurveRight className="absolute left-0 top-[34px] h-[86px] w-[62px]" />
        <span className="absolute left-[32px] top-[56px] whitespace-nowrap text-[10px] text-muted-slate">
          Config + Input
        </span>
        {/* Animated dot traveling along top curve */}
        <div className="absolute left-0 top-[34px] h-[87px] w-[62px]">
          <div
            className="z-10 size-[0.19669rem] rounded-full bg-light-slate"
            style={{
              offsetPath: `path("M0.116 0.378C5.258 1.96 18.865 13.194 32.155 45.469C45.445 77.745 57.469 86.341 61.82 86.605")`,
              animation: "travelPath 3s linear infinite",
            }}
          />
        </div>

        {/* Your agent box */}
        <div className="absolute left-[62px] top-[107px] flex items-center gap-1.5 rounded border border-light-slate bg-primary-foreground p-2.5">
          <Bot className="size-3 text-light-slate" />
          <span className="whitespace-nowrap font-mono text-[10px] leading-loose text-foreground">
            Your agent
          </span>
        </div>

        {/* Bottom curve */}
        <CurveLeft className="absolute left-0 top-[128px] h-[86px] w-[62px]" />
        {/* Animated dot traveling along bottom curve (reverse) */}
        <div className="absolute left-0 top-[128px] h-[87px] w-[62px]">
          <div
            className="z-10 size-[0.19669rem] rounded-full bg-light-slate"
            style={{
              offsetPath: `path("M61.82 0.395C57.469 0.659 45.445 9.255 32.155 41.531C18.865 73.807 5.258 85.04 0.116 86.622")`,
              animation: "travelPath 3s linear infinite",
              animationDelay: "1.5s",
            }}
          />
        </div>
        <span className="absolute left-[32px] top-[185px] whitespace-nowrap text-[10px] text-muted-slate">
          Response + trace
        </span>
      </div>
    </div>
  );
};

export default AgentSandboxFlowDiagram;
