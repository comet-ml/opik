Never hallucinate an API key. Instead, always use the API key populated in the .env file.

If an installation already exists, do not modify its code in any way.

# Feature flags

A given feature flag should be used in as few places as possible. Do not increase the risk of undefined behavior by scattering the same feature flag across multiple areas of code. If the same feature flag needs to be introduced at multiple callsites, flag this for the developer to inspect carefully.

If a job requires creating new feature flag names, make them as clear and descriptive as possible.

If using JavaScript, store flag names as strings to an object declared as a constant, to simulate an enum. If using TypeScript, use an enum. Use a consistent naming convention for this storage. enum members should be written UPPERCASE_WITH_UNDERSCORE.

Gate flag-dependent code on a check that verifies the flag's values are valid and expected.

# Identification

How PostHog identifies users and whether events are identified have significant billing consequences for an integration. Consult with the developer before writing any code to implement or alter the approach to this task.

# Custom properties

If a custom property is at any point referenced in two or more files or two or more callsites in the same file, use an enum or const object, as above in feature flags.

# Naming

Before creating any new event or property names, consult with the developer for any existing naming convention. Consistency in naming is essential. Similarly, be careful about any changes to existing naming as this may break reporting and distort data for the project.