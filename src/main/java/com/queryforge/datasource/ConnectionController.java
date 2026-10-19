package com.queryforge.datasource;

import com.queryforge.datasource.dto.ConnectionRequest;
import com.queryforge.datasource.dto.ConnectionResponse;
import com.queryforge.datasource.dto.ConnectionTestResponse;
import com.queryforge.datasource.dto.SchemaResponse;
import com.queryforge.identity.AppUserPrincipal;
import com.queryforge.datasource.ConnectionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据源管理接口。用户身份来自服务端 Session，客户端不能传 userId。
 */
@RestController
@RequestMapping("/api/v1/connections")
public class ConnectionController {

    private final ConnectionService service;

    public ConnectionController(ConnectionService service) {
        this.service = service;
    }

    @GetMapping
    public List<ConnectionResponse> list(@AuthenticationPrincipal AppUserPrincipal principal) {
        return service.list(principal.id());
    }

    @PostMapping
    public ResponseEntity<ConnectionResponse> create(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody ConnectionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(principal.id(), request));
    }

    @PatchMapping("/{id}")
    public ConnectionResponse update(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody ConnectionRequest request) {
        return service.update(principal.id(), id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id) {
        service.delete(principal.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    public ConnectionTestResponse test(
            @AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id) {
        return service.test(principal.id(), id);
    }

    @GetMapping("/{id}/schema")
    public SchemaResponse schema(
            @AuthenticationPrincipal AppUserPrincipal principal, @PathVariable Long id) {
        return service.schema(principal.id(), id);
    }
}
