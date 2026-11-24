import * as vega from "vega";
import * as vegaLite from "vega-lite";
import embed, { type EmbedOptions, type VisualizationSpec } from "vega-embed";
import { expressionInterpreter } from "vega-interpreter";

Object.assign(window, {
  vega,
  vegaLite,
  vegaEmbed: (
    element: HTMLElement | string,
    spec: VisualizationSpec | string,
    options?: EmbedOptions,
  ) =>
    embed(element, spec, {
      ast: true,
      expr: expressionInterpreter,
      actions: false,
      renderer: "svg",
      ...options,
    }),
});
