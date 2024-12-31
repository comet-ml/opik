import { BaseSchema } from "../../Schema";
import { filterObject } from "../../utils/filterObject";
import { getErrorMessageForIncorrectType } from "../../utils/getErrorMessageForIncorrectType";
import { isPlainObject } from "../../utils/isPlainObject";
import { getSchemaUtils } from "../schema-utils";
import { ObjectLikeSchema, ObjectLikeUtils } from "./types";

export function getObjectLikeUtils<Raw, Parsed>(schema: BaseSchema<Raw, Parsed>): ObjectLikeUtils<Raw, Parsed> {
    return {
        withParsedProperties: (properties) => withParsedProperties(schema, properties),
    };
}

/**
 * object-like utils are defined in one file to resolve issues with circular imports
 */

export function withParsedProperties<RawObjectShape, ParsedObjectShape, Properties>(
    objectLike: BaseSchema<RawObjectShape, ParsedObjectShape>,
    properties: { [K in keyof Properties]: Properties[K] | ((parsed: ParsedObjectShape) => Properties[K]) }
): ObjectLikeSchema<RawObjectShape, ParsedObjectShape & Properties> {
    const objectSchema: BaseSchema<RawObjectShape, ParsedObjectShape & Properties> = {
        parse: (raw, opts) => {
            const parsedObject = objectLike.parse(raw, opts);
            if (!parsedObject.ok) {
                return parsedObject;
            }

            const additionalProperties = Object.entries(properties).reduce<Record<string, any>>(
                (processed, [key, value]) => {
                    return {
                        ...processed,
                        [key]: typeof value === "function" ? value(parsedObject.value) : value,
                    };
                },
                {}
            );

            return {
                ok: true,
                value: {
                    ...parsedObject.value,
                    ...(additionalProperties as Properties),
                },
            };
        },

        json: (parsed, opts) => {
            if (!isPlainObject(parsed)) {
                return {
                    ok: false,
                    errors: [
                        {
                            path: opts?.breadcrumbsPrefix ?? [],
                            message: getErrorMessageForIncorrectType(parsed, "object"),
                        },
                    ],
                };
            }

            // strip out added properties
            const addedPropertyKeys = new Set(Object.keys(properties));
            const parsedWithoutAddedProperties = filterObject(
                parsed,
                Object.keys(parsed).filter((key) => !addedPropertyKeys.has(key))
            );

            return objectLike.json(parsedWithoutAddedProperties as ParsedObjectShape, opts);
        },

        getType: () => objectLike.getType(),
    };

    return {
        ...objectSchema,
        ...getSchemaUtils(objectSchema),
        ...getObjectLikeUtils(objectSchema),
    };
}
