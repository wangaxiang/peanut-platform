package com.peanut.module.patrol;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final Set<String> subscriptions = new HashSet<>();
    private final MqttConnectOptions options = new MqttConnectOptions();

    PatrolMqtt(@Value("${mqtt.broker-url}") String broker, @Value("${mqtt.client-id}") String id,
            @Value("${mqtt.qos}") int qos, @Value("${mqtt.username:}") String user,
            @Value("${mqtt.password:}") String password, ObjectMapper json, PatrolRepository repository)
            throws MqttException {
        this.qos = qos;
        this.json = json;
        this.repository = repository;
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
            for (String sn : repository.droneSerials()) {
                subscribe("drone/" + sn + "/status");
            }
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

    void publish(PatrolTask task, long flightId) throws Exception {
        byte[] command = json.writeValueAsBytes(Map.of("taskId", task.id(), "flightId", flightId,
                "action", "start", "route", task.routeGeojson(), "returnPolicy", "auto_land"));
        client.publish("drone/" + task.droneSn() + "/cmd/task", command, qos, false);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        if (message.isRetained()) {
            return;
        }
        com.fasterxml.jackson.databind.JsonNode data;
        try {
            data = json.readTree(new String(message.getPayload(), StandardCharsets.UTF_8));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            LOG.warn("Ignoring invalid JSON from {}", topic);
            return;
        }
        if (data == null || !data.path("flightId").isIntegralNumber() || !data.path("flightId").canConvertToLong()) {
            return;
        }
        String phase = data.path("phase").asText();
        if ("done".equals(phase) || "failed".equals(phase) || "fault".equals(phase)) {
            String raw = data.path("rawImagePath").isTextual() ? data.path("rawImagePath").asText() : null;
            if (raw != null && (raw.isBlank() || raw.length() > 1024)) {
                raw = null;
            }
            repository.handleDroneReport(data.path("flightId").asLong(), topic.split("/")[1], phase, raw);
        }
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
