
package com.fiscalmotion.ledger.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class AccountStatusFilter
        extends OncePerRequestFilter {

    private final UserRepository users;

    public AccountStatusFilter(UserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {

        Authentication auth =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication();

        if (
                auth != null &&
                        auth.isAuthenticated() &&
                        !(auth instanceof AnonymousAuthenticationToken)
        ) {

            boolean active = users
                    .findByEmailIgnoreCase(auth.getName())
                    .map(user ->
                            user.getStatus()
                                    == AppUser.Status.ACTIVE
                    )
                    .orElse(false);

            if (!active) {

                new SecurityContextLogoutHandler()
                        .logout(request, response, auth);

                response.sendError(
                        HttpServletResponse.SC_UNAUTHORIZED,
                        "Account disabled"
                );

                return;
            }
        }

        chain.doFilter(request, response);
    }
}
