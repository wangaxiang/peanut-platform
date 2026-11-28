package com.peanut.module.iot;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import com.peanut.common.ApiResult;
import com.peanut.module.patrol.PatrolDeviceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/patrol/devices")
class DeviceOperationsController {
    record ImportRequest(@jakarta.validation.constraints.NotNull UUID jobId) {}
    private final DeviceCsv csv;
    private final IotAccess access;
    private final PatrolDeviceService devices;
    private final IotCatalog catalog;
    private final JdbcClient jdbc;

    DeviceOperationsController(DeviceCsv csv,IotAccess access,PatrolDeviceService devices,IotCatalog catalog,JdbcClient jdbc) {
        this.csv=csv;
        this.access=access;
        this.devices=devices;
        this.catalog=catalog;
        this.jdbc=jdbc;
    }

    @PostMapping(path="/import/preview",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResult<DeviceCsv.Job> preview(HttpServletRequest request,@RequestPart("file") MultipartFile file) throws IOException {
        return ApiResult.ok(csv.preview(file,operator(request)));
    }

    @PostMapping("/import/execute")
    ApiResult<DeviceCsv.Job> execute(HttpServletRequest request,@jakarta.validation.Valid @RequestBody ImportRequest input) {
        return ApiResult.ok(csv.execute(input.jobId(),operator(request)));
    }

    @GetMapping("/import/{id}")
    ApiResult<DeviceCsv.Job> job(HttpServletRequest request,@PathVariable UUID id) {
        return ApiResult.ok(csv.job(id,operator(request),false));
    }

    @GetMapping("/export")
    ResponseEntity<String> export(HttpServletRequest request,@RequestParam(required=false) String deviceType,
            @RequestParam(required=false) Long fieldId,@RequestParam(required=false) String status,
            @RequestParam(required=false) Boolean online,@RequestParam(required=false) String keyword,
            @RequestParam(required=false) String source,@RequestParam(required=false) Long groupId) throws IOException {
        var actor=operator(request);
        if (fieldId!=null) actor.checkField(fieldId);
        String content=csv.export(deviceType,fieldId,status,online,keyword,source,groupId,actor);
        return ResponseEntity.ok().contentType(MediaType.valueOf("text/csv;charset=UTF-8"))
                .header("Content-Disposition","attachment; filename=patrol-devices.csv").body(content);
    }

    @Transactional
    @PostMapping("/{id}/credentials/rotate")
    public ResponseEntity<ApiResult<Map<String,String>>> rotate(HttpServletRequest request,@PathVariable long id)
            throws java.security.GeneralSecurityException {
        var actor=access.actor(request);
        actor.require(IotAccess.Role.ADMIN);
        var device=devices.get(id);
        if (!"real".equals(device.accessMode()) || "retired".equals(device.status())) throw IotCatalog.bad("凭证仅适用于未退役的真实设备");
        jdbc.sql("SELECT id FROM patrol_device WHERE id=:id FOR UPDATE").param("id",id).query(Long.class).single();
        byte[] password=new byte[32],salt=new byte[16];
        var secure=new SecureRandom();
        secure.nextBytes(password);
        secure.nextBytes(salt);
        var encoding=Base64.getUrlEncoder().withoutPadding();
        String secret=encoding.encodeToString(password);
        var spec=new PBEKeySpec(secret.toCharArray(),salt,210000,256);
        String hash;
        try {
            hash="pbkdf2-sha256:210000:"+encoding.encodeToString(salt)+":"+
                    encoding.encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
        } finally {
            spec.clearPassword();
        }
        jdbc.sql("UPDATE iot_device_credential SET enabled=false WHERE device_id=:id").param("id",id).update();
        jdbc.sql("INSERT INTO iot_device_credential(device_id,credential_name,secret_hash) VALUES (:id,:name,:hash)")
                .param("id",id).param("name",device.deviceSn()).param("hash",hash).update();
        catalog.event(id,device.fieldId(),"credential_rotated","轮换设备凭证",actor.name(),null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.ok(Map.of("deviceSn",device.deviceSn(),"secret",secret,"state","pending_broker_provisioning")));
    }

    private IotAccess.Actor operator(HttpServletRequest request) {
        var actor=access.actor(request);
        actor.require(IotAccess.Role.OPERATOR);
        return actor;
    }
}
