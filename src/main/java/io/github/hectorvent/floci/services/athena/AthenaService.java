package io.github.hectorvent.floci.services.athena;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.athena.model.*;
import io.github.hectorvent.floci.services.datalake.DuckDbEngine;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.glue.model.Table;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class AthenaService {

    private static final Logger LOG = Logger.getLogger(AthenaService.class);

    private final StorageBackend<String, QueryExecution> queryStore;
    // Bounded result cache: oldest entries evicted beyond 1000 to prevent unbounded memory growth.
    private final Map<String, List<List<String>>> queryResults = Collections.synchronizedMap(
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<List<String>>> eldest) {
                    return size() > 1000;
                }
            });
    private final GlueService glueService;
    private final DuckDbEngine duckDbEngine;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @Inject
    public AthenaService(StorageFactory storageFactory, GlueService glueService, DuckDbEngine duckDbEngine) {
        this.queryStore = storageFactory.create("athena", "queries.json", new TypeReference<Map<String, QueryExecution>>() {});
        this.glueService = glueService;
        this.duckDbEngine = duckDbEngine;
    }

    public String startQueryExecution(String query, String workGroup, String database) {
        String id = UUID.randomUUID().toString();
        QueryExecution execution = new QueryExecution(id, query, workGroup);
        queryStore.put(id, execution);

        executor.submit(() -> runQuery(id, query, database));

        return id;
    }

    private void runQuery(String id, String query, String database) {
        QueryExecution execution = queryStore.get(id).orElse(null);
        if (execution == null) {
            LOG.warnv("Query execution {0} not found in store", id);
            return;
        }

        LOG.infov("Executing query {0}: {1}", id, query);
        execution.getStatus().setState(QueryExecutionState.RUNNING);
        queryStore.put(id, execution);

        try {
            String rewrittenSql = rewriteQuery(query, database);
            LOG.debugv("Rewritten SQL for {0}: {1}", id, rewrittenSql);
            
            List<List<String>> results = duckDbEngine.executeQuery(rewrittenSql);
            LOG.infov("Query {0} succeeded with {1} rows", id, results.size() - 1);
            
            queryResults.put(id, results);
            
            execution.getStatus().setState(QueryExecutionState.SUCCEEDED);
            execution.getStatus().setCompletionDateTime(Instant.now());
        } catch (Throwable e) {
            LOG.errorv("Query {0} failed with error type {1}: {2}", id, e.getClass().getName(), e.getMessage());
            execution.getStatus().setState(QueryExecutionState.FAILED);
            execution.getStatus().setStateChangeReason(e.getMessage());
            execution.getStatus().setCompletionDateTime(Instant.now());
        }
        queryStore.put(id, execution);
    }

    private String rewriteQuery(String sql, String database) {
        for (Table table : glueService.getTables(database)) {
            String tableName = table.getName();
            String location = table.getStorageDescriptor().getLocation();
            if (location.endsWith("/")) {
                location += "**";
            }

            String duckDbSource;
            if (table.getStorageDescriptor().getInputFormat() != null
                    && table.getStorageDescriptor().getInputFormat().contains("Parquet")) {
                duckDbSource = "read_parquet('" + location + "')";
            } else {
                duckDbSource = "read_csv_auto('" + location + "')";
            }

            // Only substitute table name when it appears as a FROM or JOIN target,
            // not inside SELECT columns, WHERE clauses, or string literals.
            String pattern = "(?i)(\\bFROM\\s+|\\bJOIN\\s+)" + Pattern.quote(tableName) + "\\b";
            sql = sql.replaceAll(pattern, "$1" + Matcher.quoteReplacement(duckDbSource));
        }
        return sql;
    }

    public QueryExecution getQueryExecution(String id) {
        return queryStore.get(id)
                .orElseThrow(() -> new AwsException("InvalidRequestException", "Query execution not found: " + id, 400));
    }

    public List<QueryExecution> listQueryExecutions() {
        return queryStore.scan(k -> true);
    }

    public ResultSet getQueryResults(String id) {
        QueryExecution execution = getQueryExecution(id);
        if (execution.getStatus().getState() != QueryExecutionState.SUCCEEDED) {
            throw new AwsException("InvalidRequestException", "Query has not succeeded yet", 400);
        }

        List<List<String>> data = queryResults.get(id);
        if (data == null) return new ResultSet(List.of(), new ResultSet.ResultSetMetadata(List.of()));

        List<String> header = data.get(0);
        List<ResultSet.ColumnInfo> columnInfos = header.stream()
                .map(name -> new ResultSet.ColumnInfo(name, "varchar"))
                .toList();

        List<ResultSet.Row> rows = data.subList(1, data.size()).stream()
                .map(row -> new ResultSet.Row(row.stream().map(ResultSet.Datum::new).toList()))
                .toList();

        return new ResultSet(rows, new ResultSet.ResultSetMetadata(columnInfos));
    }
}
