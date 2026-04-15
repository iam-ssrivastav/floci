# Athena

**Protocol:** JSON 1.1
**Endpoint:** `http://localhost:4566/`

Floci emulates Amazon Athena's control-plane and query state machine. Query execution is in **mock mode** — SQL is accepted and queries immediately transition to `SUCCEEDED` with an empty result set. A dedicated query engine (DuckDB in a sidecar container) will be integrated in a future release.

## Supported Actions

| Action | Description |
|---|---|
| `StartQueryExecution` | Accepts a SQL query; transitions it to SUCCEEDED immediately |
| `GetQueryExecution` | Returns query status (QUEUED, RUNNING, SUCCEEDED, FAILED) |
| `GetQueryResults` | Returns the result set for a completed query (empty in mock mode) |
| `ListQueryExecutions` | Returns a list of past query executions |
| `StopQueryExecution` | Cancels a running query |
| `CreateWorkGroup` | Creates a new workgroup |
| `GetWorkGroup` | Returns information about a workgroup |
| `ListWorkGroups` | Lists all workgroups |

## How it works

1. **State machine**: Floci implements the full Athena execution state lifecycle (QUEUED → RUNNING → SUCCEEDED/FAILED).
2. **Mock execution**: Queries are accepted but not executed — results are always empty. This allows SDK-based workflow code to be tested end-to-end.

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Start a query
QUERY_ID=$(aws athena start-query-execution \
  --query-string "SELECT count(*) FROM my_table" \
  --query-execution-context Database=my_db \
  --query 'QueryExecutionId' \
  --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Check status
aws athena get-query-execution --query-execution-id $QUERY_ID --endpoint-url $AWS_ENDPOINT_URL

# Get results (empty in mock mode)
aws athena get-query-results --query-execution-id $QUERY_ID --endpoint-url $AWS_ENDPOINT_URL
```
