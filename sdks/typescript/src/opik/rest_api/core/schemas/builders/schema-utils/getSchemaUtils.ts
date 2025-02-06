import { BaseSchema, Schema, SchemaOptions, SchemaType } from "../../Schema";
import { JsonError } from "./JsonError";
import { ParseError } from "./ParseError";

export interface SchemaUtils<Raw, Parsed> {
    nullable: () => Schema<Raw | null, Parsed | null>;
    optional: () => Schema<Raw | null | undefined, Parsed | undefined>;
    optionalNullable: () => Schema<Raw | null | undefined, Parsed | null | undefined>;
    transform: <Transformed>(transformer: SchemaTransformer<Parsed, Transformed>) => Schema<Raw, Transformed>;
    parseOrThrow: (raw: unknown, opts?: SchemaOptions) => Parsed;
    jsonOrThrow: (raw: unknown, opts?: SchemaOptions) => Raw;
}

export interface SchemaTransformer<Parsed, Transformed> {
    transform: (parsed: Parsed) => Transformed;
    untransform: (transformed: any) => Parsed;
}

export function getSchemaUtils<Raw, Parsed>(schema: BaseSchema<Raw, Parsed>): SchemaUtils<Raw, Parsed> {
    return {
        nullable: () => nullable(schema),
        optional: () => optional(schema),
        optionalNullable: () => optionalNullable(schema),
        transform: (transformer) => transform(schema, transformer),
        parseOrThrow: (raw, opts) => {
            const parsed = schema.parse(raw, opts);
            if (parsed.ok) {
                return parsed.value;
            }
            throw new ParseError(parsed.errors);
        },
        jsonOrThrow: (parsed, opts) => {
            const raw = schema.json(parsed, opts);
            if (raw.ok) {
                return raw.value;
            }
            throw new JsonError(raw.errors);
        },
    };
}

/**
 * schema utils are defined in one file to resolve issues with circular imports
 */

export function nullable<Raw, Parsed>(schema: BaseSchema<Raw, Parsed>): Schema<Raw | null, Parsed | null> {
    const baseSchema: BaseSchema<Raw | null, Parsed | null> = {
        parse: (raw, opts) => {
            if (raw == null) {
                return {
                    ok: true,
                    value: null,
                };
            }
            return schema.parse(raw, opts);
        },
        json: (parsed, opts) => {
            if (parsed == null) {
                return {
                    ok: true,
                    value: null,
                };
            }
            return schema.json(parsed, opts);
        },
        getType: () => SchemaType.NULLABLE,
    };

    return {
        ...baseSchema,
        ...getSchemaUtils(baseSchema),
    };
}

export function optional<Raw, Parsed>(
    schema: BaseSchema<Raw, Parsed>,
): Schema<Raw | null | undefined, Parsed | undefined> {
    const baseSchema: BaseSchema<Raw | null | undefined, Parsed | undefined> = {
        parse: (raw, opts) => {
            if (raw == null) {
                return {
                    ok: true,
                    value: undefined,
                };
            }
            return schema.parse(raw, opts);
        },
        json: (parsed, opts) => {
            if (opts?.omitUndefined && parsed === undefined) {
                return {
                    ok: true,
                    value: undefined,
                };
            }
            if (parsed == null) {
                return {
                    ok: true,
                    value: null,
                };
            }
            return schema.json(parsed, opts);
        },
        getType: () => SchemaType.OPTIONAL,
    };

    return {
        ...baseSchema,
        ...getSchemaUtils(baseSchema),
    };
}

export function optionalNullable<Raw, Parsed>(
    schema: BaseSchema<Raw, Parsed>,
): Schema<Raw | null | undefined, Parsed | null | undefined> {
    const baseSchema: BaseSchema<Raw | null | undefined, Parsed | null | undefined> = {
        parse: (raw, opts) => {
            if (raw === undefined) {
                return {
                    ok: true,
                    value: undefined,
                };
            }
            if (raw === null) {
                return {
                    ok: true,
                    value: null,
                };
            }
            return schema.parse(raw, opts);
        },
        json: (parsed, opts) => {
            if (parsed === undefined) {
                return {
                    ok: true,
                    value: undefined,
                };
            }
            if (parsed === null) {
                return {
                    ok: true,
                    value: null,
                };
            }
            return schema.json(parsed, opts);
        },
        getType: () => SchemaType.OPTIONAL_NULLABLE,
    };

    return {
        ...baseSchema,
        ...getSchemaUtils(baseSchema),
    };
}

export function transform<Raw, Parsed, Transformed>(
    schema: BaseSchema<Raw, Parsed>,
    transformer: SchemaTransformer<Parsed, Transformed>,
): Schema<Raw, Transformed> {
    const baseSchema: BaseSchema<Raw, Transformed> = {
        parse: (raw, opts) => {
            const parsed = schema.parse(raw, opts);
            if (!parsed.ok) {
                return parsed;
            }
            return {
                ok: true,
                value: transformer.transform(parsed.value),
            };
        },
        json: (transformed, opts) => {
            const parsed = transformer.untransform(transformed);
            return schema.json(parsed, opts);
        },
        getType: () => schema.getType(),
    };

    return {
        ...baseSchema,
        ...getSchemaUtils(baseSchema),
    };
}
