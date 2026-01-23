import { OpikApiClient } from "@/rest_api";
import { RequestOptions } from "@/types/request";
import { Supplier } from "@/rest_api/core";

export interface OpikApiClientTempOptions extends OpikApiClient.Options {
  requestOptions?: RequestOptions;
}

export class OpikApiClientTemp extends OpikApiClient {
  public requestOptions: RequestOptions;

  constructor(options?: OpikApiClientTempOptions) {
    // Merge headers from options and requestOptions
    const mergedHeaders: Record<string, string | Supplier<string | null | undefined> | null | undefined> = {
      ...options?.headers,
      ...options?.requestOptions?.headers,
    };
    
    // Add Authorization header if apiKey is provided
    if (options?.apiKey !== undefined) {
      mergedHeaders.authorization = options.apiKey;
    }

    // Pass merged headers to parent constructor
    super({
      ...options,
      headers: mergedHeaders,
    });
    
    this.requestOptions = options?.requestOptions || {};
  }

  public setHeaders = (headers: Record<string, string>) => {
    this.requestOptions.headers = headers;
  };
}
