package dev.atoms.demo.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.ApiError;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration
public class SecurityConfig {
    @Bean HttpSessionSecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
    @Bean HttpSessionCsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper json,
            HttpSessionSecurityContextRepository contexts, HttpSessionCsrfTokenRepository csrf) throws Exception {
        return http
                // Replace only the XFO writer; keep all other Spring headers and management DENY semantics.
                .headers(headers -> headers.frameOptions(frame -> frame.disable())
                        .addHeaderWriter(new dev.atoms.demo.preview.RuntimeDocumentPolicy()))
                .securityContext(config -> config.securityContextRepository(contexts))
                .csrf(config -> config.csrfTokenRepository(csrf))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/health", "/api/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/session").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> {
                            response.setStatus(401);
                            response.setContentType("application/json;charset=UTF-8");
                            json.writeValue(response.getOutputStream(), ApiError.of(request,
                                    "SESSION_REQUIRED", "访客会话已失效，请重新连接。", false));
                        })
                        .accessDeniedHandler((request, response, error) -> {
                            response.setStatus(403);
                            response.setContentType("application/json;charset=UTF-8");
                            json.writeValue(response.getOutputStream(), ApiError.of(request,
                                    "CSRF_REJECTED", "安全验证失败，请重新连接后重试。", false));
                        }))
                .build();
    }
}
