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
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
    @Bean
    HttpSessionSecurityContextRepository securityContexts() {
        var repository = new HttpSessionSecurityContextRepository();
        repository.setDisableUrlRewriting(true);
        return repository;
    }

    @Bean
    HttpSessionCsrfTokenRepository csrfTokens() { return new HttpSessionCsrfTokenRepository(); }

    @Bean
    SecurityFilterChain endpoints(HttpSecurity http, ApiExceptionHandler apiErrors,
            HttpSessionSecurityContextRepository contexts, HttpSessionCsrfTokenRepository csrf) throws Exception {
        // Only the explicit public and authenticated application contract is reachable.
        // https://docs.spring.io/spring-security/reference/6.5/servlet/authorization/authorize-http-requests.html
        return http.authorizeHttpRequests(rules -> rules.requestMatchers(HttpMethod.GET, "/api/health", "/api/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/session").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/app", "/api/app/document", "/api/state").hasRole("GUEST")
                        .requestMatchers(HttpMethod.PUT, "/api/state").hasRole("GUEST")
                        .anyRequest().denyAll())
                .securityContext(config -> config.securityContextRepository(contexts).requireExplicitSave(true))
                .csrf(config -> config.csrfTokenRepository(csrf))
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
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
