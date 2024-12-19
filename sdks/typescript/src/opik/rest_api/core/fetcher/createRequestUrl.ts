import qs from "qs";

export function createRequestUrl(
    baseUrl: string,
    queryParameters?: Record<string, string | string[] | object | object[]>
): string {
    return Object.keys(queryParameters ?? {}).length > 0
        ? `${baseUrl}?${qs.stringify(queryParameters, { arrayFormat: "repeat" })}`
        : baseUrl;
}
