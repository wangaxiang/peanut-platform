package com.peanut.module.iot;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peanut.module.patrol.PatrolDevice;
import com.peanut.module.patrol.PatrolDeviceService;
import com.peanut.module.patrol.PatrolDeviceRequests.DeviceInput;
import jakarta.validation.Validator;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
class DeviceCsv {
    private static final String[] HEADERS={"deviceType","deviceSn","deviceName","brand","model","fieldId","parentDeviceId",
            "accessMode","protocol","installLocation","sampleIntervalSeconds","longitude","latitude","metricCodes","tags","remark"};
    record Issue(long row,String message) {}
    record Job(UUID jobId,JsonNode rows,JsonNode errors,Integer importedCount) {}

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Validator validator;
    private final PatrolDeviceService devices;
    private final IotCatalog catalog;

    DeviceCsv(JdbcClient jdbc,ObjectMapper json,Validator validator,PatrolDeviceService devices,IotCatalog catalog) {
        this.jdbc=jdbc;
        this.json=json;
        this.validator=validator;
        this.devices=devices;
        this.catalog=catalog;
    }

    Job preview(MultipartFile file,IotAccess.Actor actor) throws IOException {
        if (file.isEmpty() || file.getSize()>1_048_576) throw IotCatalog.bad("设备 CSV 需在 1 MiB 以内");
        var rows=json.createArrayNode();
        var errors=new ArrayList<Issue>();
        var seen=new HashSet<String>();
        try (var reader=new PushbackReader(new InputStreamReader(file.getInputStream(),StandardCharsets.UTF_8))) {
            int initial=reader.read();
            if (initial!=-1 && initial!=0xfeff) reader.unread(initial);
            var format=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setTrim(true).build();
            try (var csv=CSVParser.parse(reader,format)) {
                if (!csv.getHeaderNames().containsAll(List.of("deviceType","deviceSn","deviceName"))) {
                    throw IotCatalog.bad("CSV 缺少 deviceType、deviceSn 或 deviceName 表头");
                }
                for (CSVRecord line:csv) {
                    long number=line.getRecordNumber()+1;
                    if (number>501) throw IotCatalog.bad("单次导入最多 500 行");
                    try {
                        if (!line.isConsistent()) throw IotCatalog.bad("列数与表头不一致");
                        DeviceInput input=convert(line);
                        validate(input,actor);
                        if (!seen.add(input.deviceSn())) throw IotCatalog.bad("文件内设备序列号重复");
                        rows.addObject().put("row",number).set("device",json.valueToTree(input));
                    } catch (IllegalArgumentException | ResponseStatusException e) {
                        String reason=e instanceof ResponseStatusException status ? status.getReason() : "数值或字段格式无效";
                        errors.add(new Issue(number,reason));
                    }
                }
            }
        }
        if (rows.isEmpty() && errors.isEmpty()) throw IotCatalog.bad("CSV 未包含设备行");
        UUID id=UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO iot_device_import(id,actor,rows,errors) VALUES(:id,:actor,CAST(:rows AS jsonb),CAST(:errors AS jsonb))
                """).param("id",id).param("actor",actor.name()).param("rows",rows.toString())
                .param("errors",json.valueToTree(errors).toString()).update();
        return job(id,actor,false);
    }

    @Transactional
    public Job execute(UUID id,IotAccess.Actor actor) {
        Job job=job(id,actor,true);
        if (job.importedCount()!=null) return job;
        if (!job.errors().isEmpty()) throw IotCatalog.bad("请修正预览中的错误后重新上传");
        int imported=0;
        for (JsonNode line:job.rows()) {
            DeviceInput input=json.convertValue(line.path("device"),DeviceInput.class);
            try {
                validate(input,actor);
                devices.create(input,actor.name());
                imported++;
            } catch (ResponseStatusException e) {
                throw IotCatalog.bad("第 "+line.path("row").asLong()+" 行："+e.getReason());
            }
        }
        jdbc.sql("UPDATE iot_device_import SET imported_count=:count WHERE id=:id")
                .param("count",imported).param("id",id).update();
        return job(id,actor,false);
    }

    Job job(UUID id,IotAccess.Actor actor,boolean lock) {
        return jdbc.sql("""
                SELECT * FROM iot_device_import WHERE id=:id AND actor=:actor
                  AND (imported_count IS NOT NULL OR created_at>now()-interval '1 day')
                """+(lock?" FOR UPDATE":"")).param("id",id).param("actor",actor.name())
                .query((rs,n) -> new Job(id,catalog.json(rs.getString("rows")),catalog.json(rs.getString("errors")),
                        rs.getObject("imported_count",Integer.class))).optional()
                .orElseThrow(() -> IotCatalog.bad("导入预览不存在或已过期"));
    }

    String export(String type,Long field,String status,Boolean online,String keyword,String source,
            Long group,IotAccess.Actor actor) throws IOException {
        var writer=new StringWriter();
        try (var csv=new CSVPrinter(writer,CSVFormat.DEFAULT.builder().setHeader(HEADERS).build())) {
            for (int page=0;;page++) {
                var result=devices.list(type,field,status,online,keyword,null,source,group,actor.fields(),PageRequest.of(page,200));
                if (result.getTotalElements()>10000) throw IotCatalog.bad("导出上限为 10000 台，请增加筛选条件");
                for (PatrolDevice row:result.getContent()) {
                    csv.printRecord(row.deviceType(),row.deviceSn(),safe(row.deviceName()),safe(row.brand()),safe(row.model()),
                            row.fieldId(),row.parentDeviceId(),row.accessMode(),row.protocol(),safe(row.installLocation()),
                            row.sampleIntervalSeconds(),row.longitude(),row.latitude(),
                            String.join("|",IotCatalog.codes(row.metricCodes())),safe(String.join("|",IotCatalog.codes(row.tags()))),safe(row.remark()));
                }
                if (!result.hasNext()) break;
            }
        }
        return "\ufeff"+writer;
    }

    private void validate(DeviceInput input,IotAccess.Actor actor) {
        var errors=validator.validate(input);
        if (!errors.isEmpty()) throw IotCatalog.bad(errors.stream().map(error -> error.getPropertyPath()+" "+error.getMessage())
                .sorted().collect(Collectors.joining("；")));
        actor.checkField(input.fieldId());
        if (input.parentDeviceId()!=null) actor.checkField(devices.get(input.parentDeviceId()).fieldId());
        if ("simulated".equals(input.accessMode())) actor.require(IotAccess.Role.ADMIN);
        devices.preview(input);
    }

    private DeviceInput convert(CSVRecord line) {
        var row=new HashMap<String,Object>();
        for (String key:HEADERS) {
            if (!line.isMapped(key) || line.get(key).isBlank()) continue;
            String value=line.get(key);
            if (value.startsWith("'") && value.length()>1 && "=+-@".indexOf(value.charAt(1))>=0) value=value.substring(1);
            row.put(key,value);
        }
        for (String key:List.of("metricCodes","tags")) {
            if (row.containsKey(key)) row.put(key, List.of(row.get(key).toString().split("\\|")));
        }
        return json.convertValue(row,DeviceInput.class);
    }

    private static String safe(String value) {
        return value!=null && !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0))>=0 ? "'"+value : value;
    }
}
