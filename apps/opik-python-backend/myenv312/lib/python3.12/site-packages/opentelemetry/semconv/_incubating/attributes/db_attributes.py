# Copyright The OpenTelemetry Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from enum import Enum
from typing import Final

from deprecated import deprecated

DB_CASSANDRA_CONSISTENCY_LEVEL: Final = "db.cassandra.consistency_level"
"""
Deprecated: Replaced by `cassandra.consistency.level`.
"""

DB_CASSANDRA_COORDINATOR_DC: Final = "db.cassandra.coordinator.dc"
"""
Deprecated: Replaced by `cassandra.coordinator.dc`.
"""

DB_CASSANDRA_COORDINATOR_ID: Final = "db.cassandra.coordinator.id"
"""
Deprecated: Replaced by `cassandra.coordinator.id`.
"""

DB_CASSANDRA_IDEMPOTENCE: Final = "db.cassandra.idempotence"
"""
Deprecated: Replaced by `cassandra.query.idempotent`.
"""

DB_CASSANDRA_PAGE_SIZE: Final = "db.cassandra.page_size"
"""
Deprecated: Replaced by `cassandra.page.size`.
"""

DB_CASSANDRA_SPECULATIVE_EXECUTION_COUNT: Final = (
    "db.cassandra.speculative_execution_count"
)
"""
Deprecated: Replaced by `cassandra.speculative_execution.count`.
"""

DB_CASSANDRA_TABLE: Final = "db.cassandra.table"
"""
Deprecated: Replaced by `db.collection.name`.
"""

DB_CLIENT_CONNECTION_POOL_NAME: Final = "db.client.connection.pool.name"
"""
The name of the connection pool; unique within the instrumented application. In case the connection pool implementation doesn't provide a name, instrumentation SHOULD use a combination of parameters that would make the name unique, for example, combining attributes `server.address`, `server.port`, and `db.namespace`, formatted as `server.address:server.port/db.namespace`. Instrumentations that generate connection pool name following different patterns SHOULD document it.
"""

DB_CLIENT_CONNECTION_STATE: Final = "db.client.connection.state"
"""
The state of a connection in the pool.
"""

DB_CLIENT_CONNECTIONS_POOL_NAME: Final = "db.client.connections.pool.name"
"""
Deprecated: Replaced by `db.client.connection.pool.name`.
"""

DB_CLIENT_CONNECTIONS_STATE: Final = "db.client.connections.state"
"""
Deprecated: Replaced by `db.client.connection.state`.
"""

DB_COLLECTION_NAME: Final = "db.collection.name"
"""
The name of a collection (table, container) within the database.
Note: It is RECOMMENDED to capture the value as provided by the application
without attempting to do any case normalization.

The collection name SHOULD NOT be extracted from `db.query.text`,
when the database system supports cross-table queries in non-batch operations.

For batch operations, if the individual operations are known to have the same
collection name then that collection name SHOULD be used.
"""

DB_CONNECTION_STRING: Final = "db.connection_string"
"""
Deprecated: Replaced by `server.address` and `server.port`.
"""

DB_COSMOSDB_CLIENT_ID: Final = "db.cosmosdb.client_id"
"""
Deprecated: Replaced by `azure.client.id`.
"""

DB_COSMOSDB_CONNECTION_MODE: Final = "db.cosmosdb.connection_mode"
"""
Deprecated: Replaced by `azure.cosmosdb.connection.mode`.
"""

DB_COSMOSDB_CONSISTENCY_LEVEL: Final = "db.cosmosdb.consistency_level"
"""
Deprecated: Replaced by `azure.cosmosdb.consistency.level`.
"""

DB_COSMOSDB_CONTAINER: Final = "db.cosmosdb.container"
"""
Deprecated: Replaced by `db.collection.name`.
"""

DB_COSMOSDB_OPERATION_TYPE: Final = "db.cosmosdb.operation_type"
"""
Deprecated: No replacement at this time.
"""

DB_COSMOSDB_REGIONS_CONTACTED: Final = "db.cosmosdb.regions_contacted"
"""
Deprecated: Replaced by `azure.cosmosdb.operation.contacted_regions`.
"""

DB_COSMOSDB_REQUEST_CHARGE: Final = "db.cosmosdb.request_charge"
"""
Deprecated: Replaced by `azure.cosmosdb.operation.request_charge`.
"""

DB_COSMOSDB_REQUEST_CONTENT_LENGTH: Final = (
    "db.cosmosdb.request_content_length"
)
"""
Deprecated: Replaced by `azure.cosmosdb.request.body.size`.
"""

DB_COSMOSDB_STATUS_CODE: Final = "db.cosmosdb.status_code"
"""
Deprecated: Replaced by `db.response.status_code`.
"""

DB_COSMOSDB_SUB_STATUS_CODE: Final = "db.cosmosdb.sub_status_code"
"""
Deprecated: Replaced by `azure.cosmosdb.response.sub_status_code`.
"""

DB_ELASTICSEARCH_CLUSTER_NAME: Final = "db.elasticsearch.cluster.name"
"""
Deprecated: Replaced by `db.namespace`.
"""

DB_ELASTICSEARCH_NODE_NAME: Final = "db.elasticsearch.node.name"
"""
Deprecated: Replaced by `elasticsearch.node.name`.
"""

DB_ELASTICSEARCH_PATH_PARTS_TEMPLATE: Final = "db.elasticsearch.path_parts"
"""
Deprecated: Replaced by `db.operation.parameter`.
"""

DB_INSTANCE_ID: Final = "db.instance.id"
"""
Deprecated: Deprecated, no general replacement at this time. For Elasticsearch, use `db.elasticsearch.node.name` instead.
"""

DB_JDBC_DRIVER_CLASSNAME: Final = "db.jdbc.driver_classname"
"""
Deprecated: Removed as not used.
"""

DB_MONGODB_COLLECTION: Final = "db.mongodb.collection"
"""
Deprecated: Replaced by `db.collection.name`.
"""

DB_MSSQL_INSTANCE_NAME: Final = "db.mssql.instance_name"
"""
Deprecated: Deprecated, no replacement at this time.
"""

DB_NAME: Final = "db.name"
"""
Deprecated: Replaced by `db.namespace`.
"""

DB_NAMESPACE: Final = "db.namespace"
"""
The name of the database, fully qualified within the server address and port.
Note: If a database system has multiple namespace components, they SHOULD be concatenated (potentially using database system specific conventions) from most general to most specific namespace component, and more specific namespaces SHOULD NOT be captured without the more general namespaces, to ensure that "startswith" queries for the more general namespaces will be valid.
Semantic conventions for individual database systems SHOULD document what `db.namespace` means in the context of that system.
It is RECOMMENDED to capture the value as provided by the application without attempting to do any case normalization.
"""

DB_OPERATION: Final = "db.operation"
"""
Deprecated: Replaced by `db.operation.name`.
"""

DB_OPERATION_BATCH_SIZE: Final = "db.operation.batch.size"
"""
The number of queries included in a batch operation.
Note: Operations are only considered batches when they contain two or more operations, and so `db.operation.batch.size` SHOULD never be `1`.
"""

DB_OPERATION_NAME: Final = "db.operation.name"
"""
The name of the operation or command being executed.
Note: It is RECOMMENDED to capture the value as provided by the application
without attempting to do any case normalization.

The operation name SHOULD NOT be extracted from `db.query.text`,
when the database system supports cross-table queries in non-batch operations.

For batch operations, if the individual operations are known to have the same operation name
then that operation name SHOULD be used prepended by `BATCH `,
otherwise `db.operation.name` SHOULD be `BATCH` or some other database
system specific term if more applicable.
"""

DB_OPERATION_PARAMETER_TEMPLATE: Final = "db.operation.parameter"
"""
A database operation parameter, with `<key>` being the parameter name, and the attribute value being a string representation of the parameter value.
Note: If a parameter has no name and instead is referenced only by index, then `<key>` SHOULD be the 0-based index.
If `db.query.text` is also captured, then `db.operation.parameter.<key>` SHOULD match up with the parameterized placeholders present in `db.query.text`.
"""

DB_QUERY_PARAMETER_TEMPLATE: Final = "db.query.parameter"
"""
Deprecated: Replaced by `db.operation.parameter`.
"""

DB_QUERY_SUMMARY: Final = "db.query.summary"
"""
Low cardinality representation of a database query text.
Note: `db.query.summary` provides static summary of the query text. It describes a class of database queries and is useful as a grouping key, especially when analyzing telemetry for database calls involving complex queries.
Summary may be available to the instrumentation through instrumentation hooks or other means. If it is not available, instrumentations that support query parsing SHOULD generate a summary following [Generating query summary](../database/database-spans.md#generating-a-summary-of-the-query-text) section.
"""

DB_QUERY_TEXT: Final = "db.query.text"
"""
The database query being executed.
Note: For sanitization see [Sanitization of `db.query.text`](../database/database-spans.md#sanitization-of-dbquerytext).
For batch operations, if the individual operations are known to have the same query text then that query text SHOULD be used, otherwise all of the individual query texts SHOULD be concatenated with separator `; ` or some other database system specific separator if more applicable.
Even though parameterized query text can potentially have sensitive data, by using a parameterized query the user is giving a strong signal that any sensitive data will be passed as parameter values, and the benefit to observability of capturing the static part of the query text by default outweighs the risk.
"""

DB_REDIS_DATABASE_INDEX: Final = "db.redis.database_index"
"""
Deprecated: Replaced by `db.namespace`.
"""

DB_RESPONSE_RETURNED_ROWS: Final = "db.response.returned_rows"
"""
Number of rows returned by the operation.
"""

DB_RESPONSE_STATUS_CODE: Final = "db.response.status_code"
"""
Database response status code.
Note: The status code returned by the database. Usually it represents an error code, but may also represent partial success, warning, or differentiate between various types of successful outcomes.
Semantic conventions for individual database systems SHOULD document what `db.response.status_code` means in the context of that system.
"""

DB_SQL_TABLE: Final = "db.sql.table"
"""
Deprecated: Replaced by `db.collection.name`.
"""

DB_STATEMENT: Final = "db.statement"
"""
Deprecated: Replaced by `db.query.text`.
"""

DB_SYSTEM: Final = "db.system"
"""
Deprecated: Replaced by `db.system.name`.
"""

DB_SYSTEM_NAME: Final = "db.system.name"
"""
The database management system (DBMS) product as identified by the client instrumentation.
Note: The actual DBMS may differ from the one identified by the client. For example, when using PostgreSQL client libraries to connect to a CockroachDB, the `db.system.name` is set to `postgresql` based on the instrumentation's best knowledge.
"""

DB_USER: Final = "db.user"
"""
Deprecated: No replacement at this time.
"""


@deprecated(
    reason="The attribute db.cassandra.consistency_level is deprecated - Replaced by `cassandra.consistency.level`"
)  # type: ignore
class DbCassandraConsistencyLevelValues(Enum):
    ALL = "all"
    """all."""
    EACH_QUORUM = "each_quorum"
    """each_quorum."""
    QUORUM = "quorum"
    """quorum."""
    LOCAL_QUORUM = "local_quorum"
    """local_quorum."""
    ONE = "one"
    """one."""
    TWO = "two"
    """two."""
    THREE = "three"
    """three."""
    LOCAL_ONE = "local_one"
    """local_one."""
    ANY = "any"
    """any."""
    SERIAL = "serial"
    """serial."""
    LOCAL_SERIAL = "local_serial"
    """local_serial."""


class DbClientConnectionStateValues(Enum):
    IDLE = "idle"
    """idle."""
    USED = "used"
    """used."""


@deprecated(
    reason="The attribute db.client.connections.state is deprecated - Replaced by `db.client.connection.state`"
)  # type: ignore
class DbClientConnectionsStateValues(Enum):
    IDLE = "idle"
    """idle."""
    USED = "used"
    """used."""


@deprecated(
    reason="The attribute db.cosmosdb.connection_mode is deprecated - Replaced by `azure.cosmosdb.connection.mode`"
)  # type: ignore
class DbCosmosdbConnectionModeValues(Enum):
    GATEWAY = "gateway"
    """Gateway (HTTP) connection."""
    DIRECT = "direct"
    """Direct connection."""


@deprecated(
    reason="The attribute db.cosmosdb.consistency_level is deprecated - Replaced by `azure.cosmosdb.consistency.level`"
)  # type: ignore
class DbCosmosdbConsistencyLevelValues(Enum):
    STRONG = "Strong"
    """strong."""
    BOUNDED_STALENESS = "BoundedStaleness"
    """bounded_staleness."""
    SESSION = "Session"
    """session."""
    EVENTUAL = "Eventual"
    """eventual."""
    CONSISTENT_PREFIX = "ConsistentPrefix"
    """consistent_prefix."""


@deprecated(
    reason="The attribute db.cosmosdb.operation_type is deprecated - No replacement at this time"
)  # type: ignore
class DbCosmosdbOperationTypeValues(Enum):
    BATCH = "batch"
    """batch."""
    CREATE = "create"
    """create."""
    DELETE = "delete"
    """delete."""
    EXECUTE = "execute"
    """execute."""
    EXECUTE_JAVASCRIPT = "execute_javascript"
    """execute_javascript."""
    INVALID = "invalid"
    """invalid."""
    HEAD = "head"
    """head."""
    HEAD_FEED = "head_feed"
    """head_feed."""
    PATCH = "patch"
    """patch."""
    QUERY = "query"
    """query."""
    QUERY_PLAN = "query_plan"
    """query_plan."""
    READ = "read"
    """read."""
    READ_FEED = "read_feed"
    """read_feed."""
    REPLACE = "replace"
    """replace."""
    UPSERT = "upsert"
    """upsert."""


@deprecated(
    reason="The attribute db.system is deprecated - Replaced by `db.system.name`"
)  # type: ignore
class DbSystemValues(Enum):
    OTHER_SQL = "other_sql"
    """Some other SQL database. Fallback only. See notes."""
    ADABAS = "adabas"
    """Adabas (Adaptable Database System)."""
    CACHE = "cache"
    """Deprecated: Replaced by `intersystems_cache`."""
    INTERSYSTEMS_CACHE = "intersystems_cache"
    """InterSystems Caché."""
    CASSANDRA = "cassandra"
    """Apache Cassandra."""
    CLICKHOUSE = "clickhouse"
    """ClickHouse."""
    CLOUDSCAPE = "cloudscape"
    """Deprecated: Replaced by `other_sql`."""
    COCKROACHDB = "cockroachdb"
    """CockroachDB."""
    COLDFUSION = "coldfusion"
    """Deprecated: Removed."""
    COSMOSDB = "cosmosdb"
    """Microsoft Azure Cosmos DB."""
    COUCHBASE = "couchbase"
    """Couchbase."""
    COUCHDB = "couchdb"
    """CouchDB."""
    DB2 = "db2"
    """IBM Db2."""
    DERBY = "derby"
    """Apache Derby."""
    DYNAMODB = "dynamodb"
    """Amazon DynamoDB."""
    EDB = "edb"
    """EnterpriseDB."""
    ELASTICSEARCH = "elasticsearch"
    """Elasticsearch."""
    FILEMAKER = "filemaker"
    """FileMaker."""
    FIREBIRD = "firebird"
    """Firebird."""
    FIRSTSQL = "firstsql"
    """Deprecated: Replaced by `other_sql`."""
    GEODE = "geode"
    """Apache Geode."""
    H2 = "h2"
    """H2."""
    HANADB = "hanadb"
    """SAP HANA."""
    HBASE = "hbase"
    """Apache HBase."""
    HIVE = "hive"
    """Apache Hive."""
    HSQLDB = "hsqldb"
    """HyperSQL DataBase."""
    INFLUXDB = "influxdb"
    """InfluxDB."""
    INFORMIX = "informix"
    """Informix."""
    INGRES = "ingres"
    """Ingres."""
    INSTANTDB = "instantdb"
    """InstantDB."""
    INTERBASE = "interbase"
    """InterBase."""
    MARIADB = "mariadb"
    """MariaDB (This value has stability level RELEASE CANDIDATE)."""
    MAXDB = "maxdb"
    """SAP MaxDB."""
    MEMCACHED = "memcached"
    """Memcached."""
    MONGODB = "mongodb"
    """MongoDB."""
    MSSQL = "mssql"
    """Microsoft SQL Server (This value has stability level RELEASE CANDIDATE)."""
    MSSQLCOMPACT = "mssqlcompact"
    """Deprecated: Removed, use `other_sql` instead."""
    MYSQL = "mysql"
    """MySQL (This value has stability level RELEASE CANDIDATE)."""
    NEO4J = "neo4j"
    """Neo4j."""
    NETEZZA = "netezza"
    """Netezza."""
    OPENSEARCH = "opensearch"
    """OpenSearch."""
    ORACLE = "oracle"
    """Oracle Database."""
    PERVASIVE = "pervasive"
    """Pervasive PSQL."""
    POINTBASE = "pointbase"
    """PointBase."""
    POSTGRESQL = "postgresql"
    """PostgreSQL (This value has stability level RELEASE CANDIDATE)."""
    PROGRESS = "progress"
    """Progress Database."""
    REDIS = "redis"
    """Redis."""
    REDSHIFT = "redshift"
    """Amazon Redshift."""
    SPANNER = "spanner"
    """Cloud Spanner."""
    SQLITE = "sqlite"
    """SQLite."""
    SYBASE = "sybase"
    """Sybase."""
    TERADATA = "teradata"
    """Teradata."""
    TRINO = "trino"
    """Trino."""
    VERTICA = "vertica"
    """Vertica."""


class DbSystemNameValues(Enum):
    OTHER_SQL = "other_sql"
    """Some other SQL database. Fallback only."""
    SOFTWAREAG_ADABAS = "softwareag.adabas"
    """[Adabas (Adaptable Database System)](https://documentation.softwareag.com/?pf=adabas)."""
    ACTIAN_INGRES = "actian.ingres"
    """[Actian Ingres](https://www.actian.com/databases/ingres/)."""
    AWS_DYNAMODB = "aws.dynamodb"
    """[Amazon DynamoDB](https://aws.amazon.com/pm/dynamodb/)."""
    AWS_REDSHIFT = "aws.redshift"
    """[Amazon Redshift](https://aws.amazon.com/redshift/)."""
    AZURE_COSMOSDB = "azure.cosmosdb"
    """[Azure Cosmos DB](https://learn.microsoft.com/azure/cosmos-db)."""
    INTERSYSTEMS_CACHE = "intersystems.cache"
    """[InterSystems Caché](https://www.intersystems.com/products/cache/)."""
    CASSANDRA = "cassandra"
    """[Apache Cassandra](https://cassandra.apache.org/)."""
    CLICKHOUSE = "clickhouse"
    """[ClickHouse](https://clickhouse.com/)."""
    COCKROACHDB = "cockroachdb"
    """[CockroachDB](https://www.cockroachlabs.com/)."""
    COUCHBASE = "couchbase"
    """[Couchbase](https://www.couchbase.com/)."""
    COUCHDB = "couchdb"
    """[Apache CouchDB](https://couchdb.apache.org/)."""
    DERBY = "derby"
    """[Apache Derby](https://db.apache.org/derby/)."""
    ELASTICSEARCH = "elasticsearch"
    """[Elasticsearch](https://www.elastic.co/elasticsearch)."""
    FIREBIRDSQL = "firebirdsql"
    """[Firebird](https://www.firebirdsql.org/)."""
    GCP_SPANNER = "gcp.spanner"
    """[Google Cloud Spanner](https://cloud.google.com/spanner)."""
    GEODE = "geode"
    """[Apache Geode](https://geode.apache.org/)."""
    H2DATABASE = "h2database"
    """[H2 Database](https://h2database.com/)."""
    HBASE = "hbase"
    """[Apache HBase](https://hbase.apache.org/)."""
    HIVE = "hive"
    """[Apache Hive](https://hive.apache.org/)."""
    HSQLDB = "hsqldb"
    """[HyperSQL Database](https://hsqldb.org/)."""
    IBM_DB2 = "ibm.db2"
    """[IBM Db2](https://www.ibm.com/db2)."""
    IBM_INFORMIX = "ibm.informix"
    """[IBM Informix](https://www.ibm.com/products/informix)."""
    IBM_NETEZZA = "ibm.netezza"
    """[IBM Netezza](https://www.ibm.com/products/netezza)."""
    INFLUXDB = "influxdb"
    """[InfluxDB](https://www.influxdata.com/)."""
    INSTANTDB = "instantdb"
    """[Instant](https://www.instantdb.com/)."""
    MARIADB = "mariadb"
    """[MariaDB](https://mariadb.org/)."""
    MEMCACHED = "memcached"
    """[Memcached](https://memcached.org/)."""
    MONGODB = "mongodb"
    """[MongoDB](https://www.mongodb.com/)."""
    MICROSOFT_SQL_SERVER = "microsoft.sql_server"
    """[Microsoft SQL Server](https://www.microsoft.com/sql-server)."""
    MYSQL = "mysql"
    """[MySQL](https://www.mysql.com/)."""
    NEO4J = "neo4j"
    """[Neo4j](https://neo4j.com/)."""
    OPENSEARCH = "opensearch"
    """[OpenSearch](https://opensearch.org/)."""
    ORACLE_DB = "oracle.db"
    """[Oracle Database](https://www.oracle.com/database/)."""
    POSTGRESQL = "postgresql"
    """[PostgreSQL](https://www.postgresql.org/)."""
    REDIS = "redis"
    """[Redis](https://redis.io/)."""
    SAP_HANA = "sap.hana"
    """[SAP HANA](https://www.sap.com/products/technology-platform/hana/what-is-sap-hana.html)."""
    SAP_MAXDB = "sap.maxdb"
    """[SAP MaxDB](https://maxdb.sap.com/)."""
    SQLITE = "sqlite"
    """[SQLite](https://www.sqlite.org/)."""
    TERADATA = "teradata"
    """[Teradata](https://www.teradata.com/)."""
    TRINO = "trino"
    """[Trino](https://trino.io/)."""
