import type { BaseSchema, MaybeValid, SchemaOptions } from "../Schema.js";

export function maybeSkipValidation<S extends BaseSchema<Raw, Parsed>, Raw, Parsed>(schema: S): S {
    return {
        ...schema,
        json: transformAndMaybeSkipValidation(schema.json),
        parse: transformAndMaybeSkipValidation(schema.parse),
    };
}

function transformAndMaybeSkipValidation<T>(
    transform: (value: unknown, opts?: SchemaOptions) => MaybeValid<T>,
): (value: unknown, opts?: SchemaOptions) => MaybeValid<T> {
    return (value, opts): MaybeValid<T> => {
        const transformed = transform(value, opts);
        const { skipValidation = false } = opts ?? {};
        if (!transformed.ok && skipValidation) {
            // biome-ignore lint/suspicious/noConsole: allow console
            console.warn(
                [
                    "Failed to validate.",
                    ...transformed.errors.map(
                        (error) =>
                            "  - " +
                            (error.path.length > 0 ? `${error.path.join(".")}: ${error.message}` : error.message),
                    ),
                ].join("\n"),
            );

            return {
                ok: true,
                value: value as T,
            };
        } else {
            return transformed;
        }
    };
}
