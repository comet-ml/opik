import { type MaybeValid, type Schema, SchemaType, type ValidationError } from "../../Schema.js";
import { entries } from "../../utils/entries.js";
import { filterObject } from "../../utils/filterObject.js";
import { getErrorMessageForIncorrectType } from "../../utils/getErrorMessageForIncorrectType.js";
import { isPlainObject } from "../../utils/isPlainObject.js";
import { keys } from "../../utils/keys.js";
import { maybeSkipValidation } from "../../utils/maybeSkipValidation.js";
import { partition } from "../../utils/partition.js";
import { getObjectLikeUtils } from "../object-like/index.js";
import { getSchemaUtils } from "../schema-utils/index.js";
import { isProperty } from "./property.js";
import type {
    BaseObjectSchema,
    inferObjectSchemaFromPropertySchemas,
    inferParsedObjectFromPropertySchemas,
    inferRawObjectFromPropertySchemas,
    ObjectSchema,
    ObjectUtils,
    PropertySchemas,
} from "./types.js";

interface ObjectPropertyWithRawKey {
    rawKey: string;
    parsedKey: string;
    valueSchema: Schema<any, any>;
}

export function object<ParsedKeys extends string, T extends PropertySchemas<ParsedKeys>>(
    schemas: T,
): inferObjectSchemaFromPropertySchemas<T> {
    const baseSchema: BaseObjectSchema<
        inferRawObjectFromPropertySchemas<T>,
        inferParsedObjectFromPropertySchemas<T>
    > = {
        _getRawProperties: () =>
            Object.entries(schemas).map(([parsedKey, propertySchema]) =>
                isProperty(propertySchema) ? propertySchema.rawKey : parsedKey,
            ) as unknown as (keyof inferRawObjectFromPropertySchemas<T>)[],
        _getParsedProperties: () => keys(schemas) as unknown as (keyof inferParsedObjectFromPropertySchemas<T>)[],

        parse: (raw, opts) => {
            const rawKeyToProperty: Record<string, ObjectPropertyWithRawKey> = {};
            const requiredKeys: string[] = [];

            for (const [parsedKey, schemaOrObjectProperty] of entries(schemas)) {
                const rawKey = isProperty(schemaOrObjectProperty) ? schemaOrObjectProperty.rawKey : parsedKey;
                const valueSchema: Schema<any, any> = isProperty(schemaOrObjectProperty)
                    ? schemaOrObjectProperty.valueSchema
                    : schemaOrObjectProperty;

                const property: ObjectPropertyWithRawKey = {
                    rawKey,
                    parsedKey: parsedKey as string,
                    valueSchema,
                };

                rawKeyToProperty[rawKey] = property;

                if (isSchemaRequired(valueSchema)) {
                    requiredKeys.push(rawKey);
                }
            }

            return validateAndTransformObject({
                value: raw,
                requiredKeys,
                getProperty: (rawKey) => {
                    const property = rawKeyToProperty[rawKey];
                    if (property == null) {
                        return undefined;
                    }
                    return {
                        transformedKey: property.parsedKey,
                        transform: (propertyValue) =>
                            property.valueSchema.parse(propertyValue, {
                                ...opts,
                                breadcrumbsPrefix: [...(opts?.breadcrumbsPrefix ?? []), rawKey],
                            }),
                    };
                },
                unrecognizedObjectKeys: opts?.unrecognizedObjectKeys,
                skipValidation: opts?.skipValidation,
                breadcrumbsPrefix: opts?.breadcrumbsPrefix,
                omitUndefined: opts?.omitUndefined,
            });
        },

        json: (parsed, opts) => {
            const requiredKeys: string[] = [];

            for (const [parsedKey, schemaOrObjectProperty] of entries(schemas)) {
                const valueSchema: Schema<any, any> = isProperty(schemaOrObjectProperty)
                    ? schemaOrObjectProperty.valueSchema
                    : schemaOrObjectProperty;

                if (isSchemaRequired(valueSchema)) {
                    requiredKeys.push(parsedKey as string);
                }
            }

            return validateAndTransformObject({
                value: parsed,
                requiredKeys,
                getProperty: (
                    parsedKey,
                ): { transformedKey: string; transform: (propertyValue: object) => MaybeValid<any> } | undefined => {
                    const property = schemas[parsedKey as keyof T];

                    // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition
                    if (property == null) {
                        return undefined;
                    }

                    if (isProperty(property)) {
                        return {
                            transformedKey: property.rawKey,
                            transform: (propertyValue) =>
                                property.valueSchema.json(propertyValue, {
                                    ...opts,
                                    breadcrumbsPrefix: [...(opts?.breadcrumbsPrefix ?? []), parsedKey],
                                }),
                        };
                    } else {
                        return {
                            transformedKey: parsedKey,
                            transform: (propertyValue) =>
                                property.json(propertyValue, {
                                    ...opts,
                                    breadcrumbsPrefix: [...(opts?.breadcrumbsPrefix ?? []), parsedKey],
                                }),
                        };
                    }
                },
                unrecognizedObjectKeys: opts?.unrecognizedObjectKeys,
                skipValidation: opts?.skipValidation,
                breadcrumbsPrefix: opts?.breadcrumbsPrefix,
                omitUndefined: opts?.omitUndefined,
            });
        },

        getType: () => SchemaType.OBJECT,
    };

    return {
        ...maybeSkipValidation(baseSchema),
        ...getSchemaUtils(baseSchema),
        ...getObjectLikeUtils(baseSchema),
        ...getObjectUtils(baseSchema),
    };
}

function validateAndTransformObject<Transformed>({
    value,
    requiredKeys,
    getProperty,
    unrecognizedObjectKeys = "fail",
    skipValidation = false,
    breadcrumbsPrefix = [],
}: {
    value: unknown;
    requiredKeys: string[];
    getProperty: (
        preTransformedKey: string,
    ) => { transformedKey: string; transform: (propertyValue: object) => MaybeValid<any> } | undefined;
    unrecognizedObjectKeys: "fail" | "passthrough" | "strip" | undefined;
    skipValidation: boolean | undefined;
    breadcrumbsPrefix: string[] | undefined;
    omitUndefined: boolean | undefined;
}): MaybeValid<Transformed> {
    if (!isPlainObject(value)) {
        return {
            ok: false,
            errors: [
                {
                    path: breadcrumbsPrefix,
                    message: getErrorMessageForIncorrectType(value, "object"),
                },
            ],
        };
    }

    const missingRequiredKeys = new Set(requiredKeys);
    const errors: ValidationError[] = [];
    const transformed: Record<string | number | symbol, any> = {};

    for (const [preTransformedKey, preTransformedItemValue] of Object.entries(value)) {
        const property = getProperty(preTransformedKey);

        if (property != null) {
            missingRequiredKeys.delete(preTransformedKey);

            const value = property.transform(preTransformedItemValue as object);
            if (value.ok) {
                transformed[property.transformedKey] = value.value;
            } else {
                transformed[preTransformedKey] = preTransformedItemValue;
                errors.push(...value.errors);
            }
        } else {
            switch (unrecognizedObjectKeys) {
                case "fail":
                    errors.push({
                        path: [...breadcrumbsPrefix, preTransformedKey],
                        message: `Unexpected key "${preTransformedKey}"`,
                    });
                    break;
                case "strip":
                    break;
                case "passthrough":
                    transformed[preTransformedKey] = preTransformedItemValue;
                    break;
            }
        }
    }

    errors.push(
        ...requiredKeys
            .filter((key) => missingRequiredKeys.has(key))
            .map((key) => ({
                path: breadcrumbsPrefix,
                message: `Missing required key "${key}"`,
            })),
    );

    if (errors.length === 0 || skipValidation) {
        return {
            ok: true,
            value: transformed as Transformed,
        };
    } else {
        return {
            ok: false,
            errors,
        };
    }
}

export function getObjectUtils<Raw, Parsed>(schema: BaseObjectSchema<Raw, Parsed>): ObjectUtils<Raw, Parsed> {
    return {
        extend: <RawExtension, ParsedExtension>(extension: ObjectSchema<RawExtension, ParsedExtension>) => {
            const baseSchema: BaseObjectSchema<Raw & RawExtension, Parsed & ParsedExtension> = {
                _getParsedProperties: () => [...schema._getParsedProperties(), ...extension._getParsedProperties()],
                _getRawProperties: () => [...schema._getRawProperties(), ...extension._getRawProperties()],
                parse: (raw, opts) => {
                    return validateAndTransformExtendedObject({
                        extensionKeys: extension._getRawProperties(),
                        value: raw,
                        transformBase: (rawBase) => schema.parse(rawBase, opts),
                        transformExtension: (rawExtension) => extension.parse(rawExtension, opts),
                        breadcrumbsPrefix: opts?.breadcrumbsPrefix,
                    });
                },
                json: (parsed, opts) => {
                    return validateAndTransformExtendedObject({
                        extensionKeys: extension._getParsedProperties(),
                        value: parsed,
                        transformBase: (parsedBase) => schema.json(parsedBase, opts),
                        transformExtension: (parsedExtension) => extension.json(parsedExtension, opts),
                        breadcrumbsPrefix: opts?.breadcrumbsPrefix,
                    });
                },
                getType: () => SchemaType.OBJECT,
            };

            return {
                ...baseSchema,
                ...getSchemaUtils(baseSchema),
                ...getObjectLikeUtils(baseSchema),
                ...getObjectUtils(baseSchema),
            };
        },
        passthrough: () => {
            const knownRawKeys = new Set<string>(schema._getRawProperties() as string[]);
            const knownParsedKeys = new Set<string>(schema._getParsedProperties() as string[]);
            const baseSchema: BaseObjectSchema<Raw & { [key: string]: unknown }, Parsed & { [key: string]: unknown }> =
                {
                    _getParsedProperties: () => schema._getParsedProperties(),
                    _getRawProperties: () => schema._getRawProperties(),
                    parse: (raw, opts) => {
                        const transformed = schema.parse(raw, { ...opts, unrecognizedObjectKeys: "passthrough" });
                        if (!transformed.ok) {
                            return transformed;
                        }
                        const extraProperties: Record<string, unknown> = {};
                        if (typeof raw === "object" && raw != null) {
                            for (const [key, value] of Object.entries(raw)) {
                                if (!knownRawKeys.has(key)) {
                                    extraProperties[key] = value;
                                }
                            }
                        }
                        return {
                            ok: true,
                            value: {
                                ...extraProperties,
                                ...transformed.value,
                            },
                        };
                    },
                    json: (parsed, opts) => {
                        const transformed = schema.json(parsed, { ...opts, unrecognizedObjectKeys: "passthrough" });
                        if (!transformed.ok) {
                            return transformed;
                        }
                        const extraProperties: Record<string, unknown> = {};
                        if (typeof parsed === "object" && parsed != null) {
                            for (const [key, value] of Object.entries(parsed)) {
                                if (!knownParsedKeys.has(key)) {
                                    extraProperties[key] = value;
                                }
                            }
                        }
                        return {
                            ok: true,
                            value: {
                                ...extraProperties,
                                ...transformed.value,
                            },
                        };
                    },
                    getType: () => SchemaType.OBJECT,
                };

            return {
                ...baseSchema,
                ...getSchemaUtils(baseSchema),
                ...getObjectLikeUtils(baseSchema),
                ...getObjectUtils(baseSchema),
            };
        },
    };
}

function validateAndTransformExtendedObject<PreTransformedExtension, TransformedBase, TransformedExtension>({
    extensionKeys,
    value,
    transformBase,
    transformExtension,
    breadcrumbsPrefix = [],
}: {
    extensionKeys: (keyof PreTransformedExtension)[];
    value: unknown;
    transformBase: (value: object) => MaybeValid<TransformedBase>;
    transformExtension: (value: object) => MaybeValid<TransformedExtension>;
    breadcrumbsPrefix?: string[];
}): MaybeValid<TransformedBase & TransformedExtension> {
    if (!isPlainObject(value)) {
        return {
            ok: false,
            errors: [
                {
                    path: breadcrumbsPrefix,
                    message: getErrorMessageForIncorrectType(value, "object"),
                },
            ],
        };
    }

    const extensionPropertiesSet = new Set(extensionKeys);
    const [extensionProperties, baseProperties] = partition(keys(value), (key) =>
        extensionPropertiesSet.has(key as keyof PreTransformedExtension),
    );

    const transformedBase = transformBase(filterObject(value, baseProperties));
    const transformedExtension = transformExtension(filterObject(value, extensionProperties));

    if (transformedBase.ok && transformedExtension.ok) {
        return {
            ok: true,
            value: {
                ...transformedBase.value,
                ...transformedExtension.value,
            },
        };
    } else {
        return {
            ok: false,
            errors: [
                ...(transformedBase.ok ? [] : transformedBase.errors),
                ...(transformedExtension.ok ? [] : transformedExtension.errors),
            ],
        };
    }
}

function isSchemaRequired(schema: Schema<any, any>): boolean {
    return !isSchemaOptional(schema);
}

function isSchemaOptional(schema: Schema<any, any>): boolean {
    switch (schema.getType()) {
        case SchemaType.ANY:
        case SchemaType.UNKNOWN:
        case SchemaType.OPTIONAL:
        case SchemaType.OPTIONAL_NULLABLE:
            return true;
        default:
            return false;
    }
}
