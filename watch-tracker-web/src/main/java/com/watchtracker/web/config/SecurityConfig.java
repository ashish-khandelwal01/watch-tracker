package com.watchtracker.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Placeholder security configuration.
 *
 * <p>There is no user store or auth mechanism yet, so this permits every request rather than
 * leaving Spring Security's default behavior in place, which would otherwise generate a random
 * login password on every startup and block the dashboard behind a login form nobody can
 * complete. This is intentionally <b>not</b> production security — it exists so the {@code
 * spring-boot-starter-security} dependency (part of the module's intended shape per {@code
 * watch-tracker-web/README.md}) is wired in without blocking day-to-day use of the dashboard
 * while real authentication is designed.
 *
 * <p>Replace this wholesale — do not incrementally add rules to it — once real auth (user store,
 * login flow, and which endpoints require it) is decided.
 */
@Configuration
public class SecurityConfig {
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
