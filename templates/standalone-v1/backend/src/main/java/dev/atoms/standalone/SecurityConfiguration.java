package dev.atoms.standalone;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
    @Bean
    SecurityFilterChain endpoints(HttpSecurity http) throws Exception {
        // Narrow deny-by-default surface until guest/CSRF endpoints land in EB08.
        // A health probe must not create a JDBC session when the database is unavailable.
        // https://docs.spring.io/spring-security/reference/6.5/servlet/authorization/authorize-http-requests.html
        return http.authorizeHttpRequests(rules -> rules.requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                        .anyRequest().denyAll())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, failure) -> response.setStatus(401))
                        .accessDeniedHandler((request, response, failure) -> response.setStatus(403)))
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
                .build();
    }

    @Bean
    UserDetailsService noPasswordAccounts() {
        // Guest identity does not use password accounts; also prevents Boot's generated user.
        return username -> { throw new UsernameNotFoundException("Password accounts are not supported."); };
    }
}
