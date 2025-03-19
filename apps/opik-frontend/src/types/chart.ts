export type BaseDataPoint = Record<string, number | string>;
export type RadarDataPoint = BaseDataPoint & { name: string };
export type BarDataPoint = BaseDataPoint & { name: string };
