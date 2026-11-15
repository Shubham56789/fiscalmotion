
package com.fiscalmotion.ledger.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class AccessController {

    private final AuthService service;

    public AccessController(AuthService service) {
        this.service = service;
    }

    public record RequestAccess(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Email String email,
            @NotBlank @Size(max = 1000) String reason
    ) {}

    public record ChangeStatus(
            @NotNull AppUser.Status status
    ) {}

    public record RequestView(
            UUID id,
            String name,
            String email,
            String reason,
            String status,
            Instant createdAt
    ) {}

    @PostMapping("/api/v1/access-requests")
    public ResponseEntity<Void> request(
            @Valid @RequestBody RequestAccess request
    ) {
        service.request(
                request.name(),
                request.email(),
                request.reason()
        );

        return ResponseEntity.accepted().build();
    }

    @GetMapping("/api/v1/admin/access-requests")
    public List<RequestView> requests() {

        return service.requests()
                .stream()
                .map(request ->
                        new RequestView(
                                request.getId(),
                                request.getName(),
                                request.getEmail(),
                                request.getReason(),
                                request.getStatus().name(),
                                request.getCreatedAt()
                        )
                )
                .toList();
    }

    @PostMapping(
            "/api/v1/admin/access-requests/{id}/approve"
    )
    public Map<String, String> approve(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String token = service.approve(
                id,
                authentication.getName()
        );

        return Map.of("activationToken", token);
    }

    @PostMapping(
            "/api/v1/admin/access-requests/{id}/reject"
    )
    public ResponseEntity<Void> reject(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        service.reject(
                id,
                authentication.getName()
        );

        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/admin/users")
    public List<AuthService.UserView> users() {
        return service.users();
    }

    @PatchMapping("/api/v1/admin/users/{id}/status")
    public ResponseEntity<Void> status(
            @PathVariable UUID id,
            @Valid @RequestBody ChangeStatus body,
            Authentication authentication
    ) {
        service.changeStatus(
                id,
                body.status(),
                authentication.getName()
        );

        return ResponseEntity.noContent().build();
    }
}
