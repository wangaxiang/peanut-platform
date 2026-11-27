package com.peanut.module.field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

// Enable only with a disposable database that has both PostGIS and pgvector installed.
@EnabledIfEnvironmentVariable(named = "PEANUT_TEST_POSTGIS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FieldPostgisTest {
    @Autowired
    private MockMvc http;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    @Test
    void measuresAndRecalculatesAreaThroughTheFieldApi() throws Exception {
        ObjectNode request = fieldRequest("东区3号田", "花育25号", 100);
        String body = http.perform(post("/api/fields").contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("东区3号田"))
                .andExpect(jsonPath("$.data.geometry.type").value("Polygon"))
                .andExpect(jsonPath("$.data.areaMu", closeTo(15.0, 0.1)))
                .andExpect(jsonPath("$.data.currentGrowthStage").value("未播种"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        long id = json.readTree(body).path("data").path("id").asLong();

        request.put("geometry", projectedRectangle(200));
        http.perform(put("/api/fields/{id}", id).contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.areaMu", closeTo(30.0, 0.1)));

        http.perform(get("/api/fields/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.areaMu", closeTo(30.0, 0.1)));
    }

    @Test
    void searchesNameAndVarietyWithPaginationAndProvidesAreaStatistics() throws Exception {
        createField("东区3号田", "花育25号", 100);
        createField("西区1号田", "花育25号", 100);
        createField("南区2号田", "花育22号", 200);

        http.perform(get("/api/fields").param("keyword", "花育25").param("size", "1")
                        .param("page", "1").param("sort", "id,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].name").value("西区1号田"));
        http.perform(get("/api/fields").param("keyword", "东区"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].name").value("东区3号田"));
        http.perform(get("/api/fields/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andExpect(jsonPath("$.data.totalAreaMu").value(60.0))
                .andExpect(jsonPath("$.data.varietyStats[0].count").value(1));
    }

    @Test
    void deletesFarmEventsAndPatrolTasksAlongWithTheirField() throws Exception {
        long id = createField("待删除地块", "花育25号", 100);
        http.perform(post("/api/fields/{id}/events", id).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventType":"sow","eventDate":"2026-05-01","remark":"试播"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fieldId").value(id));
        http.perform(get("/api/fields/{id}/events", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].eventType").value("sow"));

        Long taskId = jdbc.queryForObject(
                "INSERT INTO patrol_task (field_id, task_name) VALUES (?, '测试巡田') RETURNING id", Long.class, id);
        jdbc.update("INSERT INTO patrol_flight (task_id, field_id) VALUES (?, ?)", taskId, id);

        http.perform(delete("/api/fields/{id}", id)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM farm_event WHERE field_id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patrol_task WHERE field_id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patrol_flight WHERE field_id = ?", Long.class, id)).isZero();
        http.perform(get("/api/fields/{id}/events", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void rejectsGeometryObjectsAndValidatesThePolygonInsideAString() throws Exception {
        ObjectNode request = fieldRequest("田块", "花育25号", 100);
        request.set("geometry", json.readTree(projectedRectangle(100)));
        http.perform(post("/api/fields").contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));

        request.put("geometry", """
                {"type":"Polygon","coordinates":[[[115,36],[116,37],[115,37],[116,36],[115,36]]]}
                """);
        http.perform(post("/api/fields").contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg", containsString("边界线有交叉")));

        request.put("geometry", """
                {"type":"Polygon","coordinates":[[[115,36],[116,36],[116,37],[115,37]]]}
                """);
        http.perform(post("/api/fields").contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg", containsString("边界没有闭合")));
    }

    @Test
    void keepsStoredFarmEventTypesAndPaginatesNewestFirst() throws Exception {
        long id = createField("农事测试田", "花育25号", 100);
        jdbc.update("""
                INSERT INTO farm_event (field_id, event_type, event_date) VALUES (?, 'sow', DATE '2026-05-02')
                """, id);

        http.perform(post("/api/fields/{id}/events", id).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventType":"fertilize","eventDate":"2026-06-02","amount":12.5,"unit":"kg"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventType").value("fertilize"))
                .andExpect(jsonPath("$.data.amount").value(12.5));
        assertThat(jdbc.queryForObject("""
                SELECT event_type FROM farm_event WHERE field_id = ? AND event_date = DATE '2026-06-02'
                """, String.class, id)).isEqualTo("fertilize");
        http.perform(get("/api/fields/{id}/events", id).param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].eventType").value("fertilize"));
        http.perform(get("/api/fields/{id}/events", id).param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].eventType").value("sow"));
    }

    @Test
    void rejectsUnknownFarmEventTypesAndAmountsWithoutUnits() throws Exception {
        long id = createField("农事校验田", "花育25号", 100);
        http.perform(post("/api/fields/{id}/events", id).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventType":"unknown","eventDate":"2026-06-02"}
                                """))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        http.perform(post("/api/fields/{id}/events", id).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventType":"irrigate","eventDate":"2026-06-02","amount":7.5}
                                """))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void appliesTheFullSchemaAndReportsHealthy() throws Exception {
        assertThat(jdbc.queryForObject("""
                SELECT success FROM flyway_schema_history WHERE version = '1'
                """, Boolean.class)).isTrue();
        assertThat(jdbc.queryForList("""
                SELECT tablename FROM pg_tables WHERE schemaname = 'public'
                """, String.class)).contains("field", "farm_event", "patrol_task", "patrol_flight",
                "pest_detection", "prescription_map", "irrigation_device", "irrigation_task",
                "knowledge_chunk", "qa_log");
        assertThat(jdbc.queryForList("""
                SELECT indexname FROM pg_indexes WHERE schemaname = 'public'
                """, String.class)).contains("idx_field_geometry", "idx_pest_field", "idx_knowledge_embedding");
        http.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    private long createField(String name, String variety, int widthMeters) throws Exception {
        String body = http.perform(post("/api/fields").contentType(MediaType.APPLICATION_JSON)
                        .content(fieldRequest(name, variety, widthMeters).toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return json.readTree(body).path("data").path("id").asLong();
    }

    private ObjectNode fieldRequest(String name, String variety, int widthMeters) throws Exception {
        ObjectNode request = json.createObjectNode().put("name", name).put("variety", variety);
        request.put("geometry", projectedRectangle(widthMeters));
        return request;
    }

    private String projectedRectangle(int widthMeters) {
        return jdbc.queryForObject("""
                SELECT ST_AsGeoJSON(ST_Transform(ST_MakeEnvelope(375000, 4000000, 375000 + ?, 4000100, 32650), 4326))
                """, String.class, widthMeters);
    }
}
