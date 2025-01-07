import { chooseStreamWrapper } from "./stream-wrappers/chooseStreamWrapper";

export async function getResponseBody(response: Response, responseType?: string): Promise<unknown> {
    if (response.body != null && responseType === "blob") {
        return await response.blob();
    } else if (response.body != null && responseType === "arrayBuffer") {
        return await response.arrayBuffer();
    } else if (response.body != null && responseType === "sse") {
        return response.body;
    } else if (response.body != null && responseType === "streaming") {
        return chooseStreamWrapper(response.body);
    } else if (response.body != null && responseType === "text") {
        return await response.text();
    } else {
        const text = await response.text();
        if (text.length > 0) {
            try {
                let responseBody = JSON.parse(text);
                return responseBody;
            } catch (err) {
                return {
                    ok: false,
                    error: {
                        reason: "non-json",
                        statusCode: response.status,
                        rawBody: text,
                    },
                };
            }
        } else {
            return undefined;
        }
    }
}
