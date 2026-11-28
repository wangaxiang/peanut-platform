package com.peanut.module.patrol;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

@Component
class PatrolMqtt implements MqttCallback {
    private static final Logger LOG = LoggerFactory.getLogger(PatrolMqtt.class);
    private final MqttClient client;
    private final int qos;
    private final ObjectMapper json;
    private final PatrolRepository repository;
    private final PatrolDeviceService devices;
    private final Path imageDirectory;
    private final Set<String> subscriptions = new HashSet<>();
    private final MqttConnectOptions options = new MqttConnectOptions();

    PatrolMqtt(@Value("${mqtt.broker-url}") String broker, @Value("${mqtt.client-id}") String id,
            @Value("${mqtt.qos}") int qos, @Value("${mqtt.username:}") String user,
            @Value("${mqtt.password:}") String password, @Value("${patrol.image-directory}") String imageDirectory,
            ObjectMapper json, PatrolRepository repository, PatrolDeviceService devices)
            throws MqttException {
        this.qos = qos;
        this.json = json;
        this.repository = repository;
        this.devices = devices;
        this.imageDirectory = Path.of(imageDirectory).toAbsolutePath().normalize();
        client = new MqttClient(broker, id, new MemoryPersistence());
        client.setTimeToWait(4000);
        client.setCallback(this);
        options.setConnectionTimeout(3);
        if (!user.isBlank()) {
            options.setUserName(user);
            options.setPassword(password.toCharArray());
        }
    }

    @Scheduled(fixedDelay = 5000)
    synchronized void refreshSubscriptions() {
        try {
            if (!client.isConnected()) {
                client.connect(options);
                subscriptions.clear();
            }
            subscribe("drone/+/status");
            subscribe("dock/+/status");
            subscribe("sensor/+/data");
        } catch (MqttException e) {
            LOG.warn("MQTT patrol subscriptions unavailable ({})", e.getReasonCode());
        }
    }

    private void subscribe(String topic) throws MqttException {
        if (!subscriptions.contains(topic)) {
            client.subscribe(topic, qos);
            subscriptions.add(topic);
        }
    }

    boolean connected() {
        return client.isConnected();
    }

    void publish(PatrolTask task, String serial, long flightId) throws Exception {
        byte[] command = json.writeValueAsBytes(Map.of("taskId", task.id(), "flightId", flightId,
                "action", "start", "route", task.routeGeojson(), "returnPolicy", "auto_land",
                "issuedAt",Instant.now().toString()));
        client.publish("drone/" + serial + "/cmd/task", command, qos, false);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        if (message.isRetained()) {
            return;
        }
        JsonNode data;
        try {
            data = json.readTree(new String(message.getPayload(), StandardCharsets.UTF_8));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            LOG.warn("Ignoring invalid JSON from {}", topic);
            return;
        }
        String[] parts = topic.split("/");
        if (data == null || parts.length != 3 || !parts[1].matches(PatrolDeviceService.SERIAL)) {
            return;
        }
        PatrolDevice device = devices.report(parts[0],parts[1],data);
        if (device == null) {
            LOG.debug("Ignored patrol telemetry for {}",topic);
            return;
        }
        if (!"drone".equals(device.deviceType()) || !data.path("flightId").isIntegralNumber()
                || !data.path("flightId").canConvertToLong()) {
            return;
        }
        String phase = data.path("phase").asText();
        if ("done".equals(phase) || "failed".equals(phase) || "fault".equals(phase)) {
            String raw = sharedImage(data.path("rawImagePath").asText(""));
            String fault = data.path("faultMessage").asText("无人机报告飞行失败");
            repository.handleDroneReport(data.path("flightId").asLong(),device.id(),device.deviceSn(),phase,
                    raw,fault.substring(0,Math.min(fault.length(),4000)));
        }
    }

    private String sharedImage(String raw) {
        if (raw.isBlank() || raw.length() > 1024) {
            return null;
        }
        try {
            Path root = imageDirectory.toRealPath();
            Path candidate = root.resolve(raw).toRealPath();
            if (candidate.startsWith(root) && Files.isRegularFile(candidate)) {
                return root.relativize(candidate).toString().replace('\\','/');
            }
        } catch (java.io.IOException | IllegalArgumentException e) {
            LOG.debug("Reported patrol image is unavailable in the shared directory");
        }
        return null;
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {}

    @Override
    public void connectionLost(Throwable cause) {
        LOG.warn("Lost MQTT patrol connection", cause);
    }

    @PreDestroy
    void close() throws MqttException {
        if (client.isConnected()) {
            client.disconnect();
        }
        client.close();
    }
}
