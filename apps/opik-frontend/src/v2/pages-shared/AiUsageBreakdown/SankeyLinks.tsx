import React from "react";

export interface RibbonPath {
  laneKey: string;
  d: string;
  color: string;
  width: number;
}

interface SankeyLinksProps {
  paths: RibbonPath[];
  width: number;
  height: number;
  highlightedKey?: string | null;
}

const SankeyLinks: React.FC<SankeyLinksProps> = ({
  paths,
  width,
  height,
  highlightedKey,
}) => (
  <svg
    className="pointer-events-none absolute inset-0"
    width={width}
    height={height}
    viewBox={`0 0 ${width} ${height}`}
    fill="none"
    aria-hidden="true"
  >
    {paths.map((path) => {
      const opacity = !highlightedKey
        ? 0.5
        : path.laneKey === highlightedKey
          ? 0.85
          : 0.15;
      return (
        <path
          key={path.laneKey}
          d={path.d}
          stroke={path.color}
          strokeWidth={path.width}
          strokeOpacity={opacity}
          strokeLinecap="round"
          fill="none"
        />
      );
    })}
  </svg>
);

export default SankeyLinks;
