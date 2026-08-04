package com.comet.opik.infrastructure.bundle;

import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.OpenOptions;
import liquibase.resource.Resource;
import liquibase.resource.ResourceAccessor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class ManagedClickHouseMigrationResourceAccessor extends ClassLoaderResourceAccessor {

    public static final String ENABLED_ENVIRONMENT_VARIABLE = "ANALYTICS_DB_MANAGED_CLICKHOUSE";

    private static final String ANALYTICS_MIGRATIONS_PREFIX = "liquibase/db-app-analytics/";
    private static final String REPLICA_ARGUMENT = "'{replica}'";
    private static final String REPLICATED_REPLACING_MERGE_TREE = "ReplicatedReplacingMergeTree";
    private static final Pattern REPLICATED_ENGINE_PATTERN = Pattern.compile(
            "(?i)\\bENGINE\\s*=\\s*(ReplicatedReplacingMergeTree|ReplicatedMergeTree)\\s*\\(");

    public static ResourceAccessor createIfEnabled() {
        return create(Boolean.parseBoolean(System.getenv(ENABLED_ENVIRONMENT_VARIABLE)));
    }

    static ResourceAccessor create(boolean enabled) {
        if (enabled) {
            return new ManagedClickHouseMigrationResourceAccessor();
        }
        return new ClassLoaderResourceAccessor();
    }

    @Override
    public List<Resource> search(String path, SearchOptions searchOptions) throws IOException {
        return wrap(super.search(path, searchOptions));
    }

    @Override
    public List<Resource> search(String path, boolean recursive) throws IOException {
        return wrap(super.search(path, recursive));
    }

    @Override
    public List<Resource> getAll(String path) throws IOException {
        return wrap(super.getAll(path));
    }

    private List<Resource> wrap(List<Resource> resources) {
        if (resources == null) {
            return null;
        }
        return resources.stream().map(this::wrap).toList();
    }

    private Resource wrap(Resource resource) {
        if (resource instanceof ManagedClickHouseMigrationResource || !isAnalyticsSql(resource.getPath())) {
            return resource;
        }
        return new ManagedClickHouseMigrationResource(resource);
    }

    private static boolean isAnalyticsSql(String path) {
        return path.startsWith(ANALYTICS_MIGRATIONS_PREFIX) && path.endsWith(".sql");
    }

    static String transform(String resourcePath, String sql) throws IOException {
        var matcher = REPLICATED_ENGINE_PATTERN.matcher(sql);
        var transformed = new StringBuilder(sql.length());
        int previousEnd = 0;

        while (matcher.find(previousEnd)) {
            int openingParenthesis = matcher.end() - 1;
            int closingParenthesis = findClosingParenthesis(resourcePath, sql, openingParenthesis);
            var engine = matcher.group(1);
            var arguments = splitArguments(resourcePath, sql.substring(openingParenthesis + 1, closingParenthesis));
            var managedArguments = classifyArguments(resourcePath, engine, arguments);

            transformed.append(sql, previousEnd, openingParenthesis + 1);
            transformed.append(String.join(", ", managedArguments));
            transformed.append(')');
            previousEnd = closingParenthesis + 1;
        }

        if (previousEnd == 0) {
            return sql;
        }
        return transformed.append(sql, previousEnd, sql.length()).toString();
    }

    private static int findClosingParenthesis(String resourcePath, String sql, int openingParenthesis)
            throws IOException {
        int depth = 0;
        boolean inString = false;

        for (int index = openingParenthesis; index < sql.length(); index++) {
            char current = sql.charAt(index);
            if (current == '\'' && inString && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                index++;
                continue;
            }
            if (current == '\'') {
                inString = !inString;
            } else if (!inString && current == '(') {
                depth++;
            } else if (!inString && current == ')' && --depth == 0) {
                return index;
            }
        }

        throw unsafeArguments(resourcePath, "unterminated replicated engine argument list");
    }

    private static List<String> splitArguments(String resourcePath, String rawArguments) throws IOException {
        if (rawArguments.isBlank()) {
            return List.of();
        }

        var arguments = new ArrayList<String>();
        int argumentStart = 0;
        int depth = 0;
        boolean inString = false;

        for (int index = 0; index < rawArguments.length(); index++) {
            char current = rawArguments.charAt(index);
            if (current == '\'' && inString && index + 1 < rawArguments.length()
                    && rawArguments.charAt(index + 1) == '\'') {
                index++;
                continue;
            }
            if (current == '\'') {
                inString = !inString;
            } else if (!inString && current == '(') {
                depth++;
            } else if (!inString && current == ')') {
                depth--;
            } else if (!inString && depth == 0 && current == ',') {
                arguments.add(rawArguments.substring(argumentStart, index).trim());
                argumentStart = index + 1;
            }
        }

        arguments.add(rawArguments.substring(argumentStart).trim());
        if (inString || depth != 0 || arguments.stream().anyMatch(String::isEmpty)) {
            throw unsafeArguments(resourcePath, "malformed replicated engine argument list");
        }
        return arguments;
    }

    private static List<String> classifyArguments(String resourcePath, String engine, List<String> arguments)
            throws IOException {
        boolean replacing = REPLICATED_REPLACING_MERGE_TREE.equalsIgnoreCase(engine);
        int maximumSemanticArguments = replacing ? 2 : 0;

        if (arguments.size() >= 2 && isZooKeeperPath(arguments.get(0)) && REPLICA_ARGUMENT.equals(arguments.get(1))) {
            var semanticArguments = arguments.subList(2, arguments.size());
            if (semanticArguments.size() <= maximumSemanticArguments) {
                return semanticArguments;
            }
        } else if (arguments.size() <= maximumSemanticArguments && arguments.stream().noneMatch(
                argument -> argument.contains("/clickhouse/") || argument.contains("{replica}"))) {
            return arguments;
        }

        throw unsafeArguments(resourcePath, "unrecognized %s argument list".formatted(engine));
    }

    private static boolean isZooKeeperPath(String argument) {
        return argument.startsWith("'") && argument.endsWith("'")
                && argument.contains("/clickhouse/tables/{shard}/");
    }

    private static IOException unsafeArguments(String resourcePath, String reason) {
        return new IOException("Cannot render managed ClickHouse migration resource '%s': %s"
                .formatted(resourcePath, reason));
    }

    private final class ManagedClickHouseMigrationResource implements Resource {

        private final Resource delegate;

        private ManagedClickHouseMigrationResource(Resource delegate) {
            this.delegate = delegate;
        }

        @Override
        public String getPath() {
            return delegate.getPath();
        }

        @Override
        public InputStream openInputStream() throws IOException {
            try (var inputStream = delegate.openInputStream()) {
                var sql = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                return new ByteArrayInputStream(transform(getPath(), sql).getBytes(StandardCharsets.UTF_8));
            }
        }

        @Override
        public boolean isWritable() {
            return delegate.isWritable();
        }

        @Override
        public boolean exists() {
            return delegate.exists();
        }

        @Override
        public Resource resolve(String other) {
            return wrap(delegate.resolve(other));
        }

        @Override
        public Resource resolveSibling(String other) {
            return wrap(delegate.resolveSibling(other));
        }

        @Override
        public OutputStream openOutputStream(OpenOptions openOptions) throws IOException {
            return delegate.openOutputStream(openOptions);
        }

        @Override
        public URI getUri() {
            return delegate.getUri();
        }

        @Override
        public String toString() {
            return delegate.toString();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (other instanceof ManagedClickHouseMigrationResource managedResource) {
                return delegate.equals(managedResource.delegate);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return delegate.hashCode();
        }
    }
}
