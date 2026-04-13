package io.github.hectorvent.floci.services.msk;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Volume;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import io.github.hectorvent.floci.services.msk.model.MskCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class RedpandaManager {

    private static final Logger LOG = Logger.getLogger(RedpandaManager.class);
    private static final int KAFKA_PORT = 9092;
    private static final int ADMIN_PORT = 9644;

    private final DockerClient dockerClient;
    private final ImageCacheService imageCacheService;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;

    @Inject
    public RedpandaManager(DockerClient dockerClient,
                           ImageCacheService imageCacheService,
                           ContainerDetector containerDetector,
                           EmulatorConfig config) {
        this.dockerClient = dockerClient;
        this.imageCacheService = imageCacheService;
        this.containerDetector = containerDetector;
        this.config = config;
    }

    public void startContainer(MskCluster cluster) {
        String image = config.services().msk().defaultImage();
        LOG.infov("Starting Redpanda container for MSK cluster: {0} using image {1}", cluster.getClusterName(), image);
        imageCacheService.ensureImageExists(image);

        String containerName = "floci-msk-" + cluster.getClusterName();

        // Ensure internal persistence path exists
        Path dataPath = Path.of(config.storage().persistentPath(), "msk", cluster.getClusterName());
        try {
            Files.createDirectories(dataPath);
        } catch (IOException e) {
            LOG.errorv("Failed to create MSK data directory: {0}", dataPath, e);
        }

        String hostPersistentPath = config.storage().hostPersistentPath();
        boolean isVolume = !hostPersistentPath.startsWith("/") && !hostPersistentPath.startsWith(".");

        HostConfig hostConfig = HostConfig.newHostConfig();
        List<String> cmd = new java.util.ArrayList<>(List.of("redpanda", "start", "--overprovisioned", "--smp", "1", "--memory", "512M", "--reserve-memory", "0M"));

        if (isVolume) {
            // Volume mode: mount the whole volume and point Redpanda to the subdirectory
            String internalMountPath = "/app/data";
            hostConfig.withBinds(new Bind(hostPersistentPath, new Volume(internalMountPath)));
            cmd.add("--data-dir");
            cmd.add(internalMountPath + "/msk/" + cluster.getClusterName());
        } else {
            // Directory mode: mount the specific subdirectory directly
            String hostDataPath = Path.of(hostPersistentPath, "msk", cluster.getClusterName())
                    .toAbsolutePath().toString();
            hostConfig.withBinds(new Bind(hostDataPath, new Volume("/var/lib/redpanda/data")));
        }

        config.services().dockerNetwork()
                .or(() -> config.services().dockerNetwork())
                .ifPresent(network -> {
                    if (!network.isBlank()) {
                        hostConfig.withNetworkMode(network);
                    }
                });

        Ports portBindings = new Ports();
        int hostKafkaPort = KAFKA_PORT;
        int hostAdminPort = ADMIN_PORT;

        if (!containerDetector.isRunningInContainer()) {
            hostKafkaPort = findFreePort();
            hostAdminPort = findFreePort();
            portBindings.bind(ExposedPort.tcp(KAFKA_PORT), Ports.Binding.bindPort(hostKafkaPort));
            portBindings.bind(ExposedPort.tcp(ADMIN_PORT), Ports.Binding.bindPort(hostAdminPort));
            hostConfig.withPortBindings(portBindings);
        }

        // Cleanup stale container
        try {
            dockerClient.removeContainerCmd(containerName).withForce(true).exec();
        } catch (Exception ignored) {}

        CreateContainerResponse container = dockerClient.createContainerCmd(image)
                .withName(containerName)
                .withExposedPorts(ExposedPort.tcp(KAFKA_PORT), ExposedPort.tcp(ADMIN_PORT))
                .withHostConfig(hostConfig)
                .withCmd(cmd)
                .exec();

        String containerId = container.getId();
        cluster.setContainerId(containerId);
        dockerClient.startContainerCmd(containerId).exec();

        String bootstrapHost;
        int bootstrapPort;
        String adminHost;
        int adminPort;

        if (!containerDetector.isRunningInContainer()) {
            bootstrapHost = "localhost";
            bootstrapPort = hostKafkaPort;
            adminHost = "localhost";
            adminPort = hostAdminPort;
        } else {
            var inspect = dockerClient.inspectContainerCmd(containerId).exec();
            String containerIp = inspect.getNetworkSettings().getNetworks().values().stream()
                    .map(com.github.dockerjava.api.model.ContainerNetwork::getIpAddress)
                    .filter(ip -> ip != null && !ip.isBlank())
                    .findFirst()
                    .orElse(inspect.getNetworkSettings().getIpAddress());
            bootstrapHost = containerIp;
            bootstrapPort = KAFKA_PORT;
            adminHost = containerIp;
            adminPort = ADMIN_PORT;
        }

        cluster.setBootstrapBrokers(bootstrapHost + ":" + bootstrapPort);
        LOG.infov("Redpanda container {0} started. Bootstrap: {1}", containerId, cluster.getBootstrapBrokers());

        // Wait for readiness in a separate thread or return and let the service handle state?
        // Service should handle the polling to CREATING -> ACTIVE transition.
    }

    public boolean isReady(MskCluster cluster) {
        String bootstrap = cluster.getBootstrapBrokers();
        if (bootstrap == null) return false;
        
        // We need the admin API to check readiness properly as per design doc (/ready endpoint)
        // For now, let's derive the admin URL. 
        // In native mode it's localhost:hostAdminPort. In container mode it's containerIp:9644.
        
        String adminUrl;
        if (!containerDetector.isRunningInContainer()) {
            // We didn't store adminPort in cluster object, let's inspect again or store it.
            // Let's store it in cluster for simplicity during development.
            // Actually, let's just inspect the container to find the binding for 9644.
            var inspect = dockerClient.inspectContainerCmd(cluster.getContainerId()).exec();
            var bindings = inspect.getNetworkSettings().getPorts().getBindings();
            var binding = bindings.get(ExposedPort.tcp(ADMIN_PORT));
            if (binding != null && binding.length > 0) {
                adminUrl = "http://localhost:" + binding[0].getHostPortSpec() + "/ready";
            } else {
                return false;
            }
        } else {
            String containerIp = bootstrap.split(":")[0];
            adminUrl = "http://" + containerIp + ":" + ADMIN_PORT + "/ready";
        }

        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(adminUrl).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public void stopContainer(MskCluster cluster) {
        if (cluster.getContainerId() == null) return;
        try {
            dockerClient.stopContainerCmd(cluster.getContainerId()).withTimeout(5).exec();
            dockerClient.removeContainerCmd(cluster.getContainerId()).withForce(true).exec();
            LOG.infov("Redpanda container {0} stopped and removed", cluster.getContainerId());
        } catch (Exception e) {
            LOG.warnv("Failed to stop Redpanda container {0}: {1}", cluster.getContainerId(), e.getMessage());
        }
    }

    private static int findFreePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Could not find a free port for Redpanda", e);
        }
    }
}
