
package com.fiscalmotion.ledger.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.*;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager manager;
    private final SecurityContextRepository contexts;
    private final AuthService service;

    public AuthController(
            AuthenticationManager manager,
            SecurityContextRepository contexts,
            AuthService service
    ) {
        this.manager = manager;
        this.contexts = contexts;
        this.service = service;
    }

    public record Login(
            @Email @NotBlank String email,
            @NotBlank String password
    ) {}

    public record Activate(
            @NotBlank String token,
            @Size(min = 12, max = 128) String password
    ) {}

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of(
                "token", token.getToken(),
                "headerName", token.getHeaderName()
        );
    }

    @PostMapping("/login")
    public Map<String, String> login(
            @Valid @RequestBody Login body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {

        Authentication auth = manager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        AuthService.normalize(body.email()),
                        body.password()
                )
        );

        request.getSession(true);
        request.changeSessionId();

        SecurityContext context =
                SecurityContextHolder.createEmptyContext();

        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        contexts.saveContext(
                context,
                request,
                response
        );

        return Map.of(
                "email", auth.getName(),
                "role", auth.getAuthorities()
                        .iterator()
                        .next()
                        .getAuthority()
                        .replace("ROLE_", "")
        );
    }

    @GetMapping("/me")
    public Map<String, String> me(
            Authentication auth
    ) {
        return Map.of(
                "email", auth.getName(),
                "role", auth.getAuthorities()
                        .iterator()
                        .next()
                        .getAuthority()
                        .replace("ROLE_", "")
        );
    }

    @PostMapping("/activate")
    public ResponseEntity<Void> activate(
            @Valid @RequestBody Activate body
    ) {
        service.activate(
                body.token(),
                body.password()
        );

        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(
            org.springframework.security.core.AuthenticationException.class
    )
    public ResponseEntity<Map<String, String>> authenticationFailure(
            org.springframework.security.core.AuthenticationException ex
    ) {
        return ResponseEntity
                .status(401)
                .body(Map.of(
                        "message", "Invalid email or password"
                ));
    }
}
