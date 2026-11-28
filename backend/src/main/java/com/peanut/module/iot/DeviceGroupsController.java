package com.peanut.module.iot;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import com.peanut.common.ApiResult;
import com.peanut.module.patrol.PatrolDeviceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/patrol/device-groups")
class DeviceGroupsController {
    record Group(long id,String groupName,String description,List<Long> deviceIds) {}
    record Input(@NotBlank @Size(max=100) String groupName,@Size(max=2000) String description) {}
    record Members(@NotNull @Size(max=250) List<@NotNull @Positive Long> deviceIds) {}
    private final JdbcClient jdbc;
    private final IotAccess access;
    private final PatrolDeviceService devices;

    DeviceGroupsController(JdbcClient jdbc,IotAccess access,PatrolDeviceService devices) {
        this.jdbc=jdbc;
        this.access=access;
        this.devices=devices;
    }

    @GetMapping
    ApiResult<List<Group>> list(HttpServletRequest request) {
        var actor=access.actor(request);
        if (actor.fields()!=null && actor.fields().isEmpty()) return ApiResult.ok(List.of());
        return ApiResult.ok(groups(actor.fields(),null));
    }

    @PostMapping
    ApiResult<Group> create(HttpServletRequest request,@Valid @RequestBody Input input) {
        admin(request);
        long id=jdbc.sql("""
                INSERT INTO iot_device_group(group_name,description) VALUES (:name,:description)
                ON CONFLICT (group_name) DO NOTHING RETURNING id
                """).param("name",input.groupName().strip()).param("description",input.description())
                .query(Long.class).optional().orElseThrow(() -> IotCatalog.bad("分组名称已存在"));
        return ApiResult.ok(get(id));
    }

    @PutMapping("/{id}")
    ApiResult<Group> update(@PathVariable long id,HttpServletRequest request,@Valid @RequestBody Input input) {
        admin(request);
        jdbc.sql("UPDATE iot_device_group SET group_name=:name,description=:description,updated_at=now() WHERE id=:id")
                .param("name",input.groupName().strip()).param("description",input.description()).param("id",id).update();
        return ApiResult.ok(get(id));
    }

    @DeleteMapping("/{id}")
    ApiResult<Map<String,Boolean>> delete(@PathVariable long id,HttpServletRequest request) {
        admin(request);
        get(id);
        jdbc.sql("DELETE FROM iot_device_group WHERE id=:id").param("id",id).update();
        return ApiResult.ok(Map.of("deleted",true));
    }

    @Transactional
    @PutMapping("/{id}/members")
    public ApiResult<Group> members(@PathVariable long id,HttpServletRequest request,@Valid @RequestBody Members input) {
        admin(request);
        jdbc.sql("SELECT id FROM iot_device_group WHERE id=:id FOR UPDATE").param("id",id).query(Long.class).optional()
                .orElseThrow(() -> IotCatalog.bad("分组不存在"));
        var chosen=input.deviceIds().stream().distinct().toList();
        for (long device:chosen) devices.get(device);
        jdbc.sql("DELETE FROM iot_device_group_member WHERE group_id=:id").param("id",id).update();
        for (long device:chosen) jdbc.sql("INSERT INTO iot_device_group_member(group_id,device_id) VALUES (:id,:device)")
                .param("id",id).param("device",device).update();
        return ApiResult.ok(get(id));
    }

    private void admin(HttpServletRequest request) { access.actor(request).require(IotAccess.Role.ADMIN); }

    private Group get(long id) {
        return groups(null,id).stream().findFirst().orElseThrow(() -> IotCatalog.bad("分组不存在"));
    }

    private List<Group> groups(List<Long> fields,Long id) {
        String where=fields!=null?" WHERE d.field_id IN (:fields)":id!=null?" WHERE g.id=:id":"";
        var query=jdbc.sql("""
                SELECT g.id,g.group_name,g.description,coalesce(array_agg(m.device_id ORDER BY m.device_id)
                    FILTER (WHERE m.device_id IS NOT NULL),ARRAY[]::bigint[]) AS ids
                FROM iot_device_group g LEFT JOIN iot_device_group_member m ON g.id=m.group_id
                LEFT JOIN patrol_device d ON d.id=m.device_id
                """ + where + " GROUP BY g.id ORDER BY g.group_name");
        if(fields!=null) query.param("fields",fields);
        if(id!=null) query.param("id",id);
        return query.query((rs,n) -> new Group(rs.getLong("id"),rs.getString("group_name"),rs.getString("description"),
                Arrays.asList((Long[])rs.getArray("ids").getArray()))).list();
    }
}
