export type ExtractTextResult = {
  text?: string;
  renderType?: "json-table";
  data?: object;
};

export type PrettifyMessageResponse = {
  message: object | string | undefined;
  prettified: boolean;
  renderType?: "text" | "json-table";
};
