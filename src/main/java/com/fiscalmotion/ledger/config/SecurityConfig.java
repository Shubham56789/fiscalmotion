
package com.fiscalmotion.ledger.config;

import com.fiscalmotion.ledger.auth.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;

import org.springframework.boot.web.servlet.FilterRegistrationBean;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories
                .createDelegatingPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(
            AuthenticationConfiguration config
    ) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            AccountStatusFilter statusFilter,
            SecurityContextRepository contexts
    ) throws Exception {

        http
                .securityContext(context ->
                        context.securityContextRepository(contexts)
                )

                .csrf(csrf ->
                        csrf.csrfTokenRepository(
                                CookieCsrfTokenRepository.withHttpOnlyFalse()
                        )
                )

                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.IF_REQUIRED
                        )
                )

                .httpBasic(basic -> basic.disable())

                .formLogin(form -> form.disable())

                .exceptionHandling(errors ->
                        errors.authenticationEntryPoint(
                                (request, response, exception) ->
                                        response.sendError(401)
                        )
                )

                .authorizeHttpRequests(auth -> auth

                        .requestMatchers(
                                "/api/v1/auth/csrf",
                                "/api/v1/auth/login",
                                "/api/v1/auth/activate",
                                "/api/v1/access-requests",
                                "/actuator/health"
                        ).permitAll()

                        .requestMatchers(
                                "/api/v1/admin/**"
                        ).hasRole("OWNER")

                        .requestMatchers(
                                "/api/v1/auth/me",
                                "/api/v1/auth/logout"
                        ).authenticated()

                        .requestMatchers(
                                "/api/v1/**"
                        ).hasAnyRole("OWNER", "USER")

                        .anyRequest().denyAll()
                )

                .logout(logout ->
                        logout
                                .logoutUrl("/api/v1/auth/logout")
                                .logoutSuccessHandler(
                                        (request, response, auth) ->
                                                response.setStatus(204)
                                )
                                .invalidateHttpSession(true)
                                .deleteCookies("JSESSIONID")
                )

                .addFilterAfter(
                        statusFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }

    @Bean
    FilterRegistrationBean<AccountStatusFilter>
    disableFilterAutoRegistration(
            AccountStatusFilter filter
    ) {

        var registration =
                new FilterRegistrationBean<>(filter);

        registration.setEnabled(false);

        return registration;
    }

    @Bean
    ApplicationRunner ownerBootstrap(
            AuthService service,

            @Value("${fiscalmotion.owner.bootstrap-enabled:false}")
            boolean enabled,

            @Value("${fiscalmotion.owner.email:}")
            String email,

            @Value("${fiscalmotion.owner.name:Owner}")
            String name,

            @Value("${fiscalmotion.owner.password:}")
            String password
    ) {

        return args -> {
            if (!enabled) {
                return;
            }

            if (email.isBlank() || password.length() < 12) {
                throw new IllegalStateException(
                        "OWNER bootstrap requires email " +
                                "and password >= 12 characters"
                );
            }

            service.bootstrap(
                    email,
                    name,
                    password
            );
        };
    }
}
