package io.github.hectorvent.floci.services.athena;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.athena.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;

@ApplicationScoped
public class AthenaService {

    private static final Logger LOG = Logger.getLogger(AthenaService.class);

    private final StorageBackend<String, QueryExecution> queryStore;

    @Inject
    public AthenaService(StorageFactory storageFactory) {
        this.queryStore = storageFactory.create("athena", "queries.json", new TypeReference<Map<String, QueryExecution>>() {});
    }

    public String startQueryExecution(String query, String workGroup, String database) {
        String id = UUID.randomUUID().toString();
        QueryExecution execution = new QueryExecution(id, query, workGroup);
        queryStore.put(id, execution);

        execution.getStatus().setState(QueryExecutionState.RUNNING);
        execution.getStatus().setState(QueryExecutionState.SUCCEEDED);
        execution.getStatus().setCompletionDateTime(Instant.now());
        queryStore.put(id, execution);

        LOG.infov("Query {0} accepted (mock mode — no execution engine)", id);
        return id;
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
        return new ResultSet(List.of(), new ResultSet.ResultSetMetadata(List.of()));
    }
}
