package dev.atoms.standalone;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
    @Bean
    SecurityFilterChain endpoints(HttpSecurity http, ApiExceptionHandler apiErrors) throws Exception {
        // Narrow deny-by-default surface until guest/CSRF endpoints land in EB08.
        // A health probe must not create a JDBC session when the database is unavailable.
        // https://docs.spring.io/spring-security/reference/6.5/servlet/authorization/authorize-http-requests.html
        return http.authorizeHttpRequests(rules -> rules.requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                        .anyRequest().denyAll())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, failure) ->
                                apiErrors.write(request, response, ApiException.Code.SESSION_REQUIRED))
                        .accessDeniedHandler((request, response, failure) -> apiErrors.write(request, response,
                                failure instanceof org.springframework.security.web.csrf.CsrfException
                                        ? ApiException.Code.CSRF_REJECTED : ApiException.Code.FORBIDDEN)))
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
                .build();
    }

    @Bean
    DefaultCookieSerializer sessionCookies(ServerProperties properties) {
        // Security/MVC may inspect the requested session even for a public STATELESS route.
        // Health must neither load nor expire an existing cookie when PostgreSQL is down.
        // All other routes retain the standard serializer and validated cookie settings.
        // https://github.com/spring-projects/spring-session/blob/3.5.7/spring-session-core/src/main/java/org/springframework/session/web/http/SessionRepositoryFilter.java
        var serializer = new DefaultCookieSerializer() {
            @Override public List<String> readCookieValues(HttpServletRequest request) {
                if ("GET".equals(request.getMethod())
                        && (request.getContextPath() + "/api/health").equals(request.getRequestURI())) return List.of();
                return super.readCookieValues(request);
            }
        };
        var cookie = properties.getServlet().getSession().getCookie();
        serializer.setCookieName(cookie.getName());
        serializer.setUseSecureCookie(cookie.getSecure());
        serializer.setUseHttpOnlyCookie(cookie.getHttpOnly());
        serializer.setSameSite(cookie.getSameSite().attributeValue());
        serializer.setCookieMaxAge(Math.toIntExact(cookie.getMaxAge().toSeconds()));
        serializer.setCookiePath("/");
        return serializer;
    }

    @Bean
    UserDetailsService noPasswordAccounts() {
        // Guest identity does not use password accounts; also prevents Boot's generated user.
        return username -> { throw new UsernameNotFoundException("Password accounts are not supported."); };
    }
}
