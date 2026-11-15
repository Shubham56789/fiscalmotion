
package com.fiscalmotion.ledger.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@Service
public class AuthService implements UserDetailsService {

    private final UserRepository users;
    private final AccessRequestRepository requests;
    private final ActivationTokenRepository tokens;
    private final AuthAuditRepository audit;
    private final PasswordEncoder encoder;

    public AuthService(
            UserRepository users,
            AccessRequestRepository requests,
            ActivationTokenRepository tokens,
            AuthAuditRepository audit,
            PasswordEncoder encoder
    ) {
        this.users = users;
        this.requests = requests;
        this.tokens = tokens;
        this.audit = audit;
        this.encoder = encoder;
    }

    public static String normalize(String email) {
        return email == null
                ? ""
                : email.trim().toLowerCase(Locale.ROOT);
    }

    public static String hash(String raw) {
        try {
            byte[] bytes = MessageDigest
                    .getInstance("SHA-256")
                    .digest(
                            raw.getBytes(StandardCharsets.UTF_8)
                    );

            return HexFormat.of().formatHex(bytes);

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {

        AppUser user = users
                .findByEmailIgnoreCase(normalize(email))
                .orElseThrow(() ->
                        new UsernameNotFoundException(
                                "Invalid credentials"
                        )
                );

        return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                user.getPasswordHash() == null
                        ? "{noop}unavailable"
                        : user.getPasswordHash(),
                user.getStatus() == AppUser.Status.ACTIVE,
                true,
                true,
                true,
                List.of(
                        new SimpleGrantedAuthority(
                                "ROLE_" + user.getRole().name()
                        )
                )
        );
    }

    @Transactional
    public void request(
            String name,
            String email,
            String reason
    ) {
        email = normalize(email);

        if (users.findByEmailIgnoreCase(email).isPresent()) {
            return;
        }

        if (requests.existsByEmailIgnoreCaseAndStatus(
                email,
                AccessRequest.Status.PENDING
        )) {
            return;
        }

        requests.save(
                new AccessRequest(
                        email,
                        name.trim(),
                        reason.trim()
                )
        );

        audit.save(
                new AuthAuditEvent(
                        "ACCESS_REQUEST",
                        email,
                        email
                )
        );
    }

    @Transactional(readOnly = true)
    public List<AccessRequest> requests() {
        return requests.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<UserView> users() {
        return users.findAll()
                .stream()
                .map(UserView::new)
                .toList();
    }

    public record UserView(
            UUID id,
            String email,
            String name,
            String role,
            String status
    ) {
        UserView(AppUser user) {
            this(
                    user.getId(),
                    user.getEmail(),
                    user.getName(),
                    user.getRole().name(),
                    user.getStatus().name()
            );
        }
    }

    @Transactional
    public String approve(UUID id, String actor) {

        AccessRequest request = requests.findById(id)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND
                        )
                );

        if (request.getStatus()
                != AccessRequest.Status.PENDING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Already decided"
            );
        }

        if (users.findByEmailIgnoreCase(
                request.getEmail()
        ).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Account exists"
            );
        }

        AppUser user = users.save(
                new AppUser(
                        request.getEmail(),
                        request.getName(),
                        AppUser.Role.USER,
                        AppUser.Status.PENDING_ACTIVATION
                )
        );

        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);

        String rawToken = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);

        tokens.save(
                new ActivationToken(
                        user,
                        hash(rawToken),
                        Instant.now().plusSeconds(86400)
                )
        );

        request.decide(AccessRequest.Status.APPROVED);

        audit.save(
                new AuthAuditEvent(
                        "APPROVE",
                        actor,
                        request.getEmail()
                )
        );

        return rawToken;
    }

    @Transactional
    public void reject(UUID id, String actor) {

        AccessRequest request = requests.findById(id)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND
                        )
                );

        if (request.getStatus()
                != AccessRequest.Status.PENDING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT
            );
        }

        request.decide(AccessRequest.Status.REJECTED);

        audit.save(
                new AuthAuditEvent(
                        "REJECT",
                        actor,
                        request.getEmail()
                )
        );
    }

    @Transactional
    public void activate(
            String rawToken,
            String password
    ) {

        ActivationToken token = tokens
                .lockByHash(hash(rawToken))
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.BAD_REQUEST,
                                "Invalid activation link"
                        )
                );

        if (!token.valid() ||
                token.getUser().getStatus()
                        != AppUser.Status.PENDING_ACTIVATION) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Expired or used activation link"
            );
        }

        token.getUser().activate(
                encoder.encode(password)
        );

        token.consume();

        audit.save(
                new AuthAuditEvent(
                        "ACTIVATE",
                        token.getUser().getEmail(),
                        token.getUser().getEmail()
                )
        );
    }

    @Transactional
    public void changeStatus(
            UUID id,
            AppUser.Status status,
            String actor
    ) {

        AppUser user = users.findById(id)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND
                        )
                );

        if (user.getRole() == AppUser.Role.OWNER) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Owner cannot be modified"
            );
        }

        if (status != AppUser.Status.ACTIVE &&
                status != AppUser.Status.SUSPENDED &&
                status != AppUser.Status.REVOKED) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST
            );
        }

        if (user.getPasswordHash() == null &&
                status == AppUser.Status.ACTIVE) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "User not activated"
            );
        }

        user.setStatus(status);

        audit.save(
                new AuthAuditEvent(
                        "STATUS_" + status,
                        actor,
                        user.getEmail()
                )
        );
    }

    @Transactional
    public void bootstrap(
            String email,
            String name,
            String password
    ) {

        if (users.existsByRole(AppUser.Role.OWNER)) {
            return;
        }

        email = normalize(email);

        if (users.findByEmailIgnoreCase(email).isPresent()) {
            throw new IllegalStateException(
                    "Bootstrap email already exists"
            );
        }

        AppUser owner = new AppUser(
                email,
                name,
                AppUser.Role.OWNER,
                AppUser.Status.ACTIVE
        );

        owner.activate(encoder.encode(password));

        users.save(owner);

        audit.save(
                new AuthAuditEvent(
                        "OWNER_BOOTSTRAP",
                        "SYSTEM",
                        email
                )
        );
    }
}
