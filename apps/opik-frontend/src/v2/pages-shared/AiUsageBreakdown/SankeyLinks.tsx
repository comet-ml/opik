import React from "react";

export interface RibbonPath {
  d: string;
  color: string;
  width: number;
}

interface SankeyLinksProps {
  paths: RibbonPath[];
  width: number;
  height: number;
}

const SankeyLinks: React.FC<SankeyLinksProps> = ({ paths, width, height }) => (
  <svg
    className="pointer-events-none absolute inset-0"
    width={width}
    height={height}
    viewBox={`0 0 ${width} ${height}`}
    fill="none"
    aria-hidden="true"
  >
    {paths.map((path, index) => (
      <path
        key={index}
        d={path.d}
        stroke={path.color}
        strokeWidth={path.width}
        strokeOpacity={0.5}
        strokeLinecap="round"
        fill="none"
      />
    ))}
  </svg>
);

export default SankeyLinks;
