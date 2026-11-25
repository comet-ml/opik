package com.comet.opik.domain;

import lombok.experimental.UtilityClass;

import java.util.Set;

/**
 * ClickHouse functions the free-form SQL accounts must not call, with every alias. They disclose the service account,
 * the server or its configuration, read data outside the row policies, or have side effects, and an analytics query
 * over a workspace's own data needs none of them. Names are lower-cased so case variants of case-insensitive functions
 * match. They are checked on the AST, before ClickHouse analyses the query, and again on the resolved query tree, which
 * reports an alias under its canonical name, as a backstop.
 *
 * <p>Reviewed against every function of the ClickHouse version the inventory in
 * {@code FreeFormSqlFunctionInventoryTest} pins: that test fails on a version with functions nobody has decided on,
 * and unless this list holds exactly the functions and aliases the inventory denies.
 */
@UtilityClass
class FreeFormSqlForbiddenFunctions {

    // The account, roles and profiles the query runs as.
    static final String CURRENT_USER = "currentuser";
    static final String CURRENT_USER_SQL_ALIAS = "current_user";
    static final String USER = "user";
    static final String AUTHENTICATED_USER = "authenticateduser";
    static final String AUTH_USER = "authuser";
    static final String CURRENT_ROLES = "currentroles";
    static final String ENABLED_ROLES = "enabledroles";
    static final String DEFAULT_ROLES = "defaultroles";
    static final String CURRENT_PROFILES = "currentprofiles";
    static final String ENABLED_PROFILES = "enabledprofiles";
    static final String DEFAULT_PROFILES = "defaultprofiles";
    static final String DISPLAY_NAME = "displayname";

    // The database and schemas the account resolves names in.
    static final String CURRENT_DATABASE = "currentdatabase";
    static final String CURRENT_DATABASE_SQL_ALIAS = "current_database";
    static final String DATABASE = "database";
    static final String SCHEMA = "schema";
    static final String CURRENT_SCHEMAS = "currentschemas";
    static final String CURRENT_SCHEMAS_SQL_ALIAS = "current_schemas";

    // Host, build and network identity of the server.
    static final String HOST_NAME = "hostname";
    static final String FQDN = "fqdn";
    static final String FULL_HOST_NAME = "fullhostname";
    static final String VERSION = "version";
    static final String REVISION = "revision";
    static final String BUILD_ID = "buildid";
    static final String SERVER_UUID = "serveruuid";
    static final String UPTIME = "uptime";
    static final String GET_OS_KERNEL_VERSION = "getoskernelversion";
    static final String GET_MACRO = "getmacro";
    static final String GET_SERVER_PORT = "getserverport";
    static final String TCP_PORT = "tcpport";
    static final String SHOW_CERTIFICATE = "showcertificate";

    // Server, table-engine and session configuration, including the request's own HTTP headers.
    static final String GET_SERVER_SETTING = "getserversetting";
    static final String GET_MERGE_TREE_SETTING = "getmergetreesetting";
    static final String GET_SETTING = "getsetting";
    static final String GET_SETTING_OR_DEFAULT = "getsettingordefault";
    static final String GLOBAL_VARIABLE = "globalvariable";
    static final String GET_CLIENT_HTTP_HEADER = "getclienthttpheader";

    // Cluster, storage and runtime state.
    static final String SHARD_NUM = "shardnum";
    static final String SHARD_COUNT = "shardcount";
    static final String ZOOKEEPER_SESSION_UPTIME = "zookeepersessionuptime";
    static final String FILESYSTEM_AVAILABLE = "filesystemavailable";
    static final String FILESYSTEM_CAPACITY = "filesystemcapacity";
    static final String FILESYSTEM_UNRESERVED = "filesystemunreserved";
    static final String CONNECTION_ID = "connectionid";
    static final String CONNECTION_ID_SQL_ALIAS = "connection_id";
    static final String HAS_THREAD_FUZZER = "hasthreadfuzzer";
    static final String TRANSACTION_ID = "transactionid";
    static final String TRANSACTION_LATEST_SNAPSHOT = "transactionlatestsnapshot";
    static final String TRANSACTION_OLDEST_SNAPSHOT = "transactionoldestsnapshot";
    static final String TID = "tid";

    // Probes for tables and databases by name, outside the account's table allowlist.
    static final String HAS_COLUMN_IN_TABLE = "hascolumnintable";
    static final String GET_MAX_TABLE_NAME_LENGTH_FOR_DATABASE = "getmaxtablenamelengthfordatabase";

    // Side effects, server files and resource use: Keeper writes, server log writes, file and model reads, sleeps.
    static final String GENERATE_SERIAL_ID = "generateserialid";
    static final String LOG_TRACE = "logtrace";
    static final String FILE = "file";
    static final String CATBOOST_EVALUATE = "catboostevaluate";
    static final String SLEEP = "sleep";
    static final String SLEEP_EACH_ROW = "sleepeachrow";

    // Stack and symbol introspection of the server binary.
    static final String ADDRESS_TO_LINE = "addresstoline";
    static final String ADDRESS_TO_LINE_WITH_INLINES = "addresstolinewithinlines";
    static final String ADDRESS_TO_SYMBOL = "addresstosymbol";
    static final String DEMANGLE = "demangle";

    // Data read by name from Join tables and dictionaries, outside the row policies.
    static final String JOIN_GET = "joinget";
    static final String JOIN_GET_OR_NULL = "joingetornull";
    static final String DICT_GET = "dictget";
    static final String DICT_GET_ALL = "dictgetall";
    static final String DICT_GET_CHILDREN = "dictgetchildren";
    static final String DICT_GET_DATE = "dictgetdate";
    static final String DICT_GET_DATE_OR_DEFAULT = "dictgetdateordefault";
    static final String DICT_GET_DATE_TIME = "dictgetdatetime";
    static final String DICT_GET_DATE_TIME_OR_DEFAULT = "dictgetdatetimeordefault";
    static final String DICT_GET_DESCENDANTS = "dictgetdescendants";
    static final String DICT_GET_FLOAT32 = "dictgetfloat32";
    static final String DICT_GET_FLOAT32_OR_DEFAULT = "dictgetfloat32ordefault";
    static final String DICT_GET_FLOAT64 = "dictgetfloat64";
    static final String DICT_GET_FLOAT64_OR_DEFAULT = "dictgetfloat64ordefault";
    static final String DICT_GET_HIERARCHY = "dictgethierarchy";
    static final String DICT_GET_IPV4 = "dictgetipv4";
    static final String DICT_GET_IPV4_OR_DEFAULT = "dictgetipv4ordefault";
    static final String DICT_GET_IPV6 = "dictgetipv6";
    static final String DICT_GET_IPV6_OR_DEFAULT = "dictgetipv6ordefault";
    static final String DICT_GET_INT16 = "dictgetint16";
    static final String DICT_GET_INT16_OR_DEFAULT = "dictgetint16ordefault";
    static final String DICT_GET_INT32 = "dictgetint32";
    static final String DICT_GET_INT32_OR_DEFAULT = "dictgetint32ordefault";
    static final String DICT_GET_INT64 = "dictgetint64";
    static final String DICT_GET_INT64_OR_DEFAULT = "dictgetint64ordefault";
    static final String DICT_GET_INT8 = "dictgetint8";
    static final String DICT_GET_INT8_OR_DEFAULT = "dictgetint8ordefault";
    static final String DICT_GET_KEYS = "dictgetkeys";
    static final String DICT_GET_OR_DEFAULT = "dictgetordefault";
    static final String DICT_GET_OR_NULL = "dictgetornull";
    static final String DICT_GET_STRING = "dictgetstring";
    static final String DICT_GET_STRING_OR_DEFAULT = "dictgetstringordefault";
    static final String DICT_GET_UINT16 = "dictgetuint16";
    static final String DICT_GET_UINT16_OR_DEFAULT = "dictgetuint16ordefault";
    static final String DICT_GET_UINT32 = "dictgetuint32";
    static final String DICT_GET_UINT32_OR_DEFAULT = "dictgetuint32ordefault";
    static final String DICT_GET_UINT64 = "dictgetuint64";
    static final String DICT_GET_UINT64_OR_DEFAULT = "dictgetuint64ordefault";
    static final String DICT_GET_UINT8 = "dictgetuint8";
    static final String DICT_GET_UINT8_OR_DEFAULT = "dictgetuint8ordefault";
    static final String DICT_GET_UUID = "dictgetuuid";
    static final String DICT_GET_UUID_OR_DEFAULT = "dictgetuuidordefault";
    static final String DICT_HAS = "dicthas";
    static final String DICT_IS_IN = "dictisin";

    static final Set<String> ALL = Set.of(
            CURRENT_USER, CURRENT_USER_SQL_ALIAS, USER, AUTHENTICATED_USER, AUTH_USER,
            CURRENT_ROLES, ENABLED_ROLES, DEFAULT_ROLES, CURRENT_PROFILES,
            ENABLED_PROFILES, DEFAULT_PROFILES, DISPLAY_NAME, CURRENT_DATABASE,
            CURRENT_DATABASE_SQL_ALIAS, DATABASE, SCHEMA, CURRENT_SCHEMAS, CURRENT_SCHEMAS_SQL_ALIAS,
            HOST_NAME, FQDN, FULL_HOST_NAME, VERSION, REVISION,
            BUILD_ID, SERVER_UUID, UPTIME, GET_OS_KERNEL_VERSION,
            GET_MACRO, GET_SERVER_PORT, TCP_PORT, SHOW_CERTIFICATE,
            GET_SERVER_SETTING, GET_MERGE_TREE_SETTING, GET_SETTING, GET_SETTING_OR_DEFAULT,
            GLOBAL_VARIABLE, GET_CLIENT_HTTP_HEADER, SHARD_NUM, SHARD_COUNT,
            ZOOKEEPER_SESSION_UPTIME, FILESYSTEM_AVAILABLE, FILESYSTEM_CAPACITY, FILESYSTEM_UNRESERVED,
            CONNECTION_ID, CONNECTION_ID_SQL_ALIAS, HAS_THREAD_FUZZER, TRANSACTION_ID, TRANSACTION_LATEST_SNAPSHOT,
            TRANSACTION_OLDEST_SNAPSHOT, TID, HAS_COLUMN_IN_TABLE, GET_MAX_TABLE_NAME_LENGTH_FOR_DATABASE,
            GENERATE_SERIAL_ID, LOG_TRACE, FILE, CATBOOST_EVALUATE,
            SLEEP, SLEEP_EACH_ROW, ADDRESS_TO_LINE, ADDRESS_TO_LINE_WITH_INLINES,
            ADDRESS_TO_SYMBOL, DEMANGLE, JOIN_GET, JOIN_GET_OR_NULL,
            DICT_GET, DICT_GET_ALL, DICT_GET_CHILDREN, DICT_GET_DATE,
            DICT_GET_DATE_OR_DEFAULT, DICT_GET_DATE_TIME, DICT_GET_DATE_TIME_OR_DEFAULT, DICT_GET_DESCENDANTS,
            DICT_GET_FLOAT32, DICT_GET_FLOAT32_OR_DEFAULT, DICT_GET_FLOAT64, DICT_GET_FLOAT64_OR_DEFAULT,
            DICT_GET_HIERARCHY, DICT_GET_IPV4, DICT_GET_IPV4_OR_DEFAULT, DICT_GET_IPV6,
            DICT_GET_IPV6_OR_DEFAULT, DICT_GET_INT16, DICT_GET_INT16_OR_DEFAULT, DICT_GET_INT32,
            DICT_GET_INT32_OR_DEFAULT, DICT_GET_INT64, DICT_GET_INT64_OR_DEFAULT, DICT_GET_INT8,
            DICT_GET_INT8_OR_DEFAULT, DICT_GET_KEYS, DICT_GET_OR_DEFAULT, DICT_GET_OR_NULL,
            DICT_GET_STRING, DICT_GET_STRING_OR_DEFAULT, DICT_GET_UINT16, DICT_GET_UINT16_OR_DEFAULT,
            DICT_GET_UINT32, DICT_GET_UINT32_OR_DEFAULT, DICT_GET_UINT64, DICT_GET_UINT64_OR_DEFAULT,
            DICT_GET_UINT8, DICT_GET_UINT8_OR_DEFAULT, DICT_GET_UUID, DICT_GET_UUID_OR_DEFAULT,
            DICT_HAS, DICT_IS_IN);

    /**
     * The forbidden functions ClickHouse also calls when the name is written bare, as in {@code SELECT user}. Since any
     * identifier could be one, these names are rejected wherever the AST holds them, including a caller's own alias or
     * CTE name; readable tables have no columns with these names.
     */
    static final Set<String> BARE_NAMES = Set.of(
            CURRENT_USER, CURRENT_USER_SQL_ALIAS, USER, CURRENT_DATABASE, CURRENT_DATABASE_SQL_ALIAS, DATABASE, SCHEMA);
}
