package io.github.hectorvent.floci.services.firehose;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.datalake.DuckDbEngine;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription;
import io.github.hectorvent.floci.services.firehose.model.Record;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class FirehoseService {

    private static final Logger LOG = Logger.getLogger(FirehoseService.class);

    private final StorageBackend<String, DeliveryStreamDescription> streamStore;
    private final Map<String, List<byte[]>> buffers = new ConcurrentHashMap<>();
    private final S3Service s3Service;
    private final DuckDbEngine duckDbEngine;

    @Inject
    public FirehoseService(StorageFactory storageFactory, S3Service s3Service, DuckDbEngine duckDbEngine) {
        this.streamStore = storageFactory.create("firehose", "streams.json", new TypeReference<Map<String, DeliveryStreamDescription>>() {});
        this.s3Service = s3Service;
        this.duckDbEngine = duckDbEngine;
    }

    public String createDeliveryStream(String name) {
        String arn = "arn:aws:firehose:us-east-1:000000000000:deliverystream/" + name;
        DeliveryStreamDescription description = new DeliveryStreamDescription(name, arn);
        streamStore.put(name, description);
        buffers.put(name, Collections.synchronizedList(new ArrayList<>()));
        LOG.infov("Created Firehose Delivery Stream: {0}", name);
        return arn;
    }

    public DeliveryStreamDescription describeDeliveryStream(String name) {
        return streamStore.get(name)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Stream not found: " + name, 400));
    }

    public List<String> listDeliveryStreams() {
        return streamStore.scan(k -> true).stream().map(DeliveryStreamDescription::getDeliveryStreamName).toList();
    }

    public void putRecord(String streamName, Record record) {
        describeDeliveryStream(streamName); // Check exists
        buffers.get(streamName).add(record.getData());
        
        // For local emulation, we flush every 5 records to make it observable
        if (buffers.get(streamName).size() >= 5) {
            flush(streamName);
        }
    }

    public void flush(String streamName) {
        List<byte[]> buffer = buffers.get(streamName);
        if (buffer.isEmpty()) return;

        List<byte[]> toFlush;
        synchronized (buffer) {
            toFlush = new ArrayList<>(buffer);
            buffer.clear();
        }

        Path tempJson = null;
        Path tempParquet = null;
        try {
            tempJson = Files.createTempFile("firehose-" + streamName + "-", ".json");
            StringBuilder sb = new StringBuilder();
            for (byte[] data : toFlush) {
                sb.append(new String(data, StandardCharsets.UTF_8)).append("\n");
            }
            Files.writeString(tempJson, sb.toString());

            String bucket = "floci-firehose-results";
            String key = streamName + "/" + UUID.randomUUID() + ".parquet";

            try {
                s3Service.createBucket(bucket, "us-east-1");
            } catch (Exception ignored) {}

            if (duckDbEngine.isAvailable()) {
                // Convert JSON → Parquet locally via DuckDB, then upload via S3Service.
                // Writing to a local temp file avoids the httpfs circular-S3 dependency.
                tempParquet = Files.createTempFile("firehose-" + streamName + "-", ".parquet");
                String sql = String.format(
                        "COPY (SELECT * FROM read_json_auto('%s')) TO '%s' (FORMAT PARQUET);",
                        tempJson.toAbsolutePath(), tempParquet.toAbsolutePath());
                duckDbEngine.executeUpdate(sql);

                byte[] parquetBytes = Files.readAllBytes(tempParquet);
                s3Service.putObject(bucket, key, parquetBytes, "application/octet-stream", java.util.Map.of());
                LOG.infov("Flushed {0} records from stream {1} to s3://{2}/{3} (Parquet)", toFlush.size(), streamName, bucket, key);
            } else {
                // DuckDB unavailable (native image): store raw NDJSON instead.
                String jsonKey = streamName + "/" + UUID.randomUUID() + ".json";
                byte[] jsonBytes = Files.readAllBytes(tempJson);
                s3Service.putObject(bucket, jsonKey, jsonBytes, "application/x-ndjson", java.util.Map.of());
                LOG.infov("Flushed {0} records from stream {1} to s3://{2}/{3} (NDJSON, DuckDB unavailable)",
                          toFlush.size(), streamName, bucket, jsonKey);
            }
        } catch (Exception e) {
            LOG.errorv("Failed to flush Firehose stream {0}: {1}", streamName, e.getMessage());
        } finally {
            silentDelete(tempJson);
            silentDelete(tempParquet);
        }
    }

    private void silentDelete(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                LOG.warnv("Could not delete temp file {0}: {1}", path, e.getMessage());
            }
        }
    }
}
