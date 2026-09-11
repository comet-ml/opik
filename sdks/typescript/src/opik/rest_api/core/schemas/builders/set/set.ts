import { type BaseSchema, type Schema, SchemaType } from "../../Schema.js";
import { getErrorMessageForIncorrectType } from "../../utils/getErrorMessageForIncorrectType.js";
import { maybeSkipValidation } from "../../utils/maybeSkipValidation.js";
import { list } from "../list/index.js";
import { getSchemaUtils } from "../schema-utils/index.js";

export function set<Raw, Parsed>(schema: Schema<Raw, Parsed>): Schema<Raw[], Set<Parsed>> {
    const listSchema = list(schema);
    const baseSchema: BaseSchema<Raw[], Set<Parsed>> = {
        parse: (raw, opts) => {
            const parsedList = listSchema.parse(raw, opts);
            if (parsedList.ok) {
                return {
                    ok: true,
                    value: new Set(parsedList.value),
                };
            } else {
                return parsedList;
            }
        },
        json: (parsed, opts) => {
            if (!(parsed instanceof Set)) {
                return {
                    ok: false,
                    errors: [
                        {
                            path: opts?.breadcrumbsPrefix ?? [],
                            message: getErrorMessageForIncorrectType(parsed, "Set"),
                        },
                    ],
                };
            }
            const jsonList = listSchema.json([...parsed], opts);
            return jsonList;
        },
        getType: () => SchemaType.SET,
    };

    return {
        ...maybeSkipValidation(baseSchema),
        ...getSchemaUtils(baseSchema),
    };
}
