import { BaseSchema, Schema, SchemaOptions, SchemaType } from "../../Schema";
import { JsonError } from "./JsonError";
import { ParseError } from "./ParseError";

export interface SchemaUtils<Raw, Parsed> {
    optional: () => Schema<Raw | null | undefined, Parsed | undefined>;
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
        optional: () => optional(schema),
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

export function optional<Raw, Parsed>(
    schema: BaseSchema<Raw, Parsed>
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

export function transform<Raw, Parsed, Transformed>(
    schema: BaseSchema<Raw, Parsed>,
    transformer: SchemaTransformer<Parsed, Transformed>
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
