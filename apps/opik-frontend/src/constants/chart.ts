export const DEFAULT_CHART_TICK = {
  stroke: "var(--chart-tick-stroke)",
  fontWeight: 200,
  letterSpacing: "0.05rem",
  fontSize: "10px",
  lineheight: "12px",
};

export const DEFAULT_CHART_GRID_PROPS = {
  stroke: "var(--chart-grid-stroke)",
};

export enum CHART_TYPE {
  line = "line",
  bar = "bar",
  radar = "radar",
}
