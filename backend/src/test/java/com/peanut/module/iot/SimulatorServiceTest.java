package com.peanut.module.iot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

class SimulatorServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final SimulatorService service = new SimulatorService(null, json, null, null, null, null);
    private final IotCatalog.Metric moisture = new IotCatalog.Metric("soil_moisture_vwc_pct",
            "Soil moisture", "soil", "%VWC", 2, 0d, 100d, "", 1, true);

    @Test
    void seedAndSampleIndexReproduceTheSameReading() {
        var profile = profile("normal", 2, 0.8);
        assertEquals(service.value(moisture, profile), service.value(moisture, profile("normal", 2, 0.8)));
    }

    @Test
    void drynessReducesMoistureAndStuckSensorStaysAtBaseline() {
        assertEquals(25.0, service.value(moisture, profile("soil_dry", 5, 0)));
        assertEquals(45.0, service.value(moisture, profile("sensor_stuck", 5, 0.8)));
    }

    private SimulatorService.Profile profile(String scenario, long step, double noise) {
        var config = json.createObjectNode();
        config.putObject("metrics").putObject(moisture.code()).put("baseline", 45).put("noise", noise);
        return new SimulatorService.Profile(1, scenario, "running", 5, 27, config, null,
                Instant.parse("2026-11-28T08:00:00Z"), new UUID(0, 1), step, step);
    }
}
