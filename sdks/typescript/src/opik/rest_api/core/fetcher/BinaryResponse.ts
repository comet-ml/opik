export type BinaryResponse = {
    /** [MDN Reference](https://developer.mozilla.org/docs/Web/API/Request/bodyUsed) */
    bodyUsed: Response["bodyUsed"];
    /**
     * Returns a ReadableStream of the response body.
     * [MDN Reference](https://developer.mozilla.org/docs/Web/API/Request/body)
     */
    stream: () => Response["body"];
    /** [MDN Reference](https://developer.mozilla.org/docs/Web/API/Request/arrayBuffer) */
    arrayBuffer: () => ReturnType<Response["arrayBuffer"]>;
    /** [MDN Reference](https://developer.mozilla.org/docs/Web/API/Request/blob) */
    blob: () => ReturnType<Response["blob"]>;
    /**
     * [MDN Reference](https://developer.mozilla.org/docs/Web/API/Request/bytes)
     * Some versions of the Fetch API may not support this method.
     */
    bytes?(): ReturnType<Response["bytes"]>;
};

export function getBinaryResponse(response: Response): BinaryResponse {
    const binaryResponse: BinaryResponse = {
        get bodyUsed() {
            return response.bodyUsed;
        },
        stream: () => response.body,
        arrayBuffer: response.arrayBuffer.bind(response),
        blob: response.blob.bind(response),
    };
    if ("bytes" in response && typeof response.bytes === "function") {
        binaryResponse.bytes = response.bytes.bind(response);
    }

    return binaryResponse;
}
