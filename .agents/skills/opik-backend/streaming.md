# Cursor Streams

Bulk-read endpoints pair a paged endpoint with a cursor stream: `POST .../stream`, NDJSON, at most 2,000 rows per
request. Callers page by sending a cursor taken from the last row they received, and stop at the first page that comes
back shorter than the limit. Unlike `LIMIT/OFFSET`, a page costs about the same no matter how deep into the data it is.

Existing streams to copy from:

| Stream | Resource | DAO query | Cursor |
|---|---|---|---|
| Traces / spans | `TracesResource`, `SpansResource` | `SELECT_BY_PROJECT_ID` (shared with paging) | `last_retrieved_id` + UUIDv7 week bound |
| Experiments | `ExperimentsResource.streamExperiments` | `ExperimentDAO.FIND` (shared) | `last_retrieved_id` |
| Experiment items | `ExperimentsResource.streamExperimentItems` | `ExperimentItemDAO.STREAM` (dedicated) | `last_retrieved_id` |
| Dataset items | `DatasetsResource.streamDatasetItems` | `SELECT_DATASET_ITEM_VERSIONS` (shared) | `last_retrieved_id` |
| A dataset's experiment items | `DatasetsResource.streamDatasetExperimentItems` | `STREAM_DATASET_EXPERIMENT_ITEMS` (dedicated) | `last_retrieved_experiment_id` + `last_retrieved_id` |

## Request Record

```java
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FooStreamRequest(
        @NotBlank String fooName,
        @Schema(defaultValue = "2000") @Min(1) @Max(2000) Integer limit,
        UUID lastRetrievedId,
        @Schema(defaultValue = "true") Boolean truncate) {

    @Override
    public Integer limit() {
        return limit == null ? 2000 : limit;
    }

    @Override
    public Boolean truncate() {
        return truncate == null || truncate;
    }
}
```

- `limit`: `@Min(1) @Max(2000)` with the default in the accessor. A violation is a 422 like any bean-validation error.
- Defaults belong in the accessor, not in `@DefaultValue`. That is a JAX-RS annotation and does nothing for a JSON body,
  so a primitive `boolean truncate` silently defaults to `false`.
- Cursor fields are named `last_retrieved_*` and are all optional. When the cursor has more than one field, require
  all or none with an `@AssertTrue` method (see `DatasetExperimentItemStreamRequest`).

## Resource

```java
@POST
@Path("/foos/stream")
@Produces(MediaType.APPLICATION_OCTET_STREAM)
@Operation(operationId = "streamFoos", summary = "Stream foos", description = "Stream foos", responses = {
        @ApiResponse(responseCode = "200", description = "Foos stream or error during process", content = @Content(array = @ArraySchema(schema = @Schema(anyOf = {
                Foo.class,
                ErrorMessage.class
        }), maxItems = 2000)))
})
public ChunkedOutput<JsonNode> streamFoos(
        @RequestBody(content = @Content(schema = @Schema(implementation = FooStreamRequest.class))) @NotNull @Valid FooStreamRequest request) {
    var ctxSnapshot = requestContext.get();
    var parentId = service.resolveParent(request); // throws NotFoundException → 404
    var items = service.stream(parentId, request)
            .contextWrite(ctx -> setRequestContext(ctx, ctxSnapshot));
    return streamer.getOutputStream(items);
}
```

- **Do anything that should fail as a 4xx before you build the `Flux`.** Once `streamer.getOutputStream` returns, the
  response is a 200, and an error raised inside the `Flux` is written as an `ErrorMessage` line in the stream body.
  Resolve names, versions and visibility synchronously in the resource or service and let `NotFoundException` escape.
- Take a snapshot of `requestContext.get()` on the request thread and pass it in with `contextWrite`. The `Flux` runs
  on a scheduler thread, where the request scope doesn't exist.
- No results means an empty `Flux`, which becomes an empty body. `streamDatasetItems` deliberately turns a visibility
  `NotFoundException` into an empty stream so it doesn't reveal whether the dataset exists. Pick one behavior and
  document it.
- `Streamer` serializes rows with `JsonUtils.readTree`, so `@JsonView` doesn't apply and every non-null field is sent.

## DAO

### Order and cursor

The cursor predicate and the `ORDER BY` must describe the same total order. Otherwise the stream skips or repeats rows
at page boundaries. (The snippets below show plain SQL; inside a StringTemplate text block a literal `<` is written as
`\\<`.)

```sql
SELECT ... FROM foos
WHERE workspace_id = :workspace_id
<if(last_retrieved_id)>AND id < :last_retrieved_id<endif>
ORDER BY id DESC, last_updated_at DESC
LIMIT 1 BY id
LIMIT :limit
```

**Choose a cursor that follows the table's sort key.** The cursor predicate is only a range read when its columns are a
prefix of the sort key (after the columns fixed by equality, such as `workspace_id`). Otherwise ClickHouse reads every
in-scope row on every page, then sorts and drops all but `:limit` of them, so the cost of a full read grows with the
square of the data.

- `traces`/`spans` (`(workspace_id, project_id, id)` plus a week bound derived from the UUIDv7 id): a single
  `last_retrieved_id` is a range read.
- `experiment_items` (`(workspace_id, experiment_id, dataset_item_id, trace_id, id)`): `id` is the last key column and
  has no skip index, so `id < :cursor` prunes nothing. `STREAM_DATASET_EXPERIMENT_ITEMS` orders by
  `(experiment_id, dataset_item_id, id)` instead and writes the cursor as nested `OR`s, which ClickHouse can analyze
  against the key:

  ```sql
  AND (experiment_id < :cursor_experiment_id
      OR (experiment_id = :cursor_experiment_id
          AND (dataset_item_id < :cursor_dataset_item_id
              OR (dataset_item_id = :cursor_dataset_item_id AND id < :cursor_id))))
  ```

Keep the request cursor to what a row identifies unambiguously. In that stream a row reports the *stable*
`dataset_item_id`, while the sort key holds the *stored* one (legacy items store an older version's row id). So
callers send only `experiment_id` and `id`, and the DAO looks up the stored `dataset_item_id` before the page query. If
the cursor row is gone, resume from the start of its experiment: repeating rows is recoverable, skipping them is not.

### Rules

- **Deduplicate before the limit.** Tables are `ReplacingMergeTree`: `ORDER BY ..., last_updated_at DESC LIMIT 1 BY id`,
  then `LIMIT :limit`. Duplicates of a row share every key column, so they are adjacent in key order.
- **Apply every filter before `LIMIT`.** A row dropped after the limit shortens the page, and a short page is how the
  caller knows the stream has ended, so it silently loses every later page. Express late filters as `IN` sets on a key
  column in the page CTE.
- **Select keys first, enrich after.** Page over the narrow key columns, then join traces, scores and comments for those
  rows only. If several CTEs reference the page, materialize it once as a scalar
  (`arrayJoin((SELECT groupArray(tuple(...)) FROM page))`): ClickHouse re-inlines a CTE at every reference.
- **Shared or dedicated query.** Add `<if(last_retrieved_id)>` to the paged template when the stream returns the same
  entity in the same order and only the paging clause changes (traces, spans, experiments, dataset items). Write a
  dedicated query when the stream's grain or order differs from the paged query's, as with experiment items, where the
  paged query nests them under dataset items.

## How Clients Read a Stream

The Python SDK pages through every stream with `rest_stream_parser.read_and_parse_full_stream`:

- it requests `limit` rows, appends them, and stops at the first page shorter than `limit`;
- it takes the cursor from the last row the backend *sent*, including rows that failed to parse, so an unreadable row
  is not served again;
- it halves the page size and retries from the same cursor when a page fails for size-related reasons.

The helper assumes the cursor is a single `id`. A stream with a multi-field cursor has to generalize it rather than
loop by hand: make the cursor opaque, built from the last row by a callback that callers supply.

## Testing

Resource tests should cover:

- **Exactly-once across pages.** Use a small `limit` (2) so the read spans several pages, page with the cursor
  until a short page arrives, and assert the full id list in order (`containsExactlyElementsOf`), not just the set.
  Cover data that crosses the cursor's grouping, such as several experiments per item.
- **Filters narrow the stream** and still end with a short page.
- **Validation:** `limit` of 2001 and 0, and an incomplete multi-field cursor, return 422.
- **Not found:** an unknown parent returns 404 before the stream starts, not an error line.
- **Empty:** no rows means an empty body.
- **Parity:** if the stream mirrors a paged endpoint, assert the streamed rows equal the paged endpoint's rows.

See `DatasetsResourceTest.FindDatasetItemsWithAllDatasetExperimentItems` (`streamWhen*` tests) and its `streamAll`
helper.
