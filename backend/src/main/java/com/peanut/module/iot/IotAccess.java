package com.peanut.module.iot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class IotAccess {
    public enum Role { VIEWER, OPERATOR, ADMIN }

    public record Actor(String name, Role role, List<Long> fields) {
        public void require(Role minimum) {
            if (role.ordinal() < minimum.ordinal()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色没有此操作权限");
            }
        }

        public void checkField(Long fieldId) {
            if (fields != null && (fieldId == null || !fields.contains(fieldId))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色无法访问该地块");
            }
        }
    }

    private final String admin;
    private final String operator;
    private final String viewer;
    private final boolean localRead;
    private final List<Long> operatorFields;
    private final List<Long> viewerFields;

    IotAccess(@Value("${iot.access.admin-token:}") String admin,
            @Value("${iot.access.operator-token:}") String operator, @Value("${iot.access.viewer-token:}") String viewer,
            @Value("${iot.access.anonymous-read:false}") boolean localRead,
            @Value("${iot.access.operator-fields:}") String operatorFields,
            @Value("${iot.access.viewer-fields:}") String viewerFields) {
        this.admin = admin;
        this.operator = operator;
        this.viewer = viewer;
        this.localRead = localRead;
        this.operatorFields = fields(operatorFields);
        this.viewerFields = fields(viewerFields);
    }

    public Actor actor(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null && localRead) return new Actor("本机只读",Role.VIEWER,null);
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            if (matches(admin,token)) return new Actor("设备管理员",Role.ADMIN,null);
            if (matches(operator,token)) return new Actor("设备运维",Role.OPERATOR,operatorFields);
            if (matches(viewer,token)) return new Actor("设备查看者",Role.VIEWER,viewerFields);
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请输入有效的设备访问令牌");
    }

    private static boolean matches(String expected, String token) {
        return !expected.isBlank() && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    private static List<Long> fields(String input) {
        return Arrays.stream(input.split(",")).map(String::strip).filter(value -> !value.isEmpty())
                .map(Long::valueOf).distinct().toList();
    }
}
