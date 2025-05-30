import { OpikApiClient } from "@/rest_api";
import { RequestOptions } from "@/types/request";

export interface OpikApiClientTempOptions extends OpikApiClient.Options {
  requestOptions?: RequestOptions;
}

export class OpikApiClientTemp extends OpikApiClient {
  public requestOptions: RequestOptions;

  constructor(options?: OpikApiClientTempOptions) {
    super(options);
    this.requestOptions = options?.requestOptions || {};
  }

  public setHeaders = (headers: Record<string, string>) => {
    this.requestOptions.headers = headers;
  };
}
