import { toJson } from "../json.js";
import { toQueryString } from "../url/qs.js";

export declare namespace GetRequestBody {
    interface Args {
        body: unknown;
        type: "json" | "file" | "bytes" | "form" | "other";
    }
}

export async function getRequestBody({ body, type }: GetRequestBody.Args): Promise<BodyInit | undefined> {
    if (type === "form") {
        return toQueryString(body, { arrayFormat: "repeat", encode: true });
    }
    if (type.includes("json")) {
        return toJson(body);
    } else {
        return body as BodyInit;
    }
}
