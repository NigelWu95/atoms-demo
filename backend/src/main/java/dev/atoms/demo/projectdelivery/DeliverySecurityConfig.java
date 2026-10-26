package dev.atoms.demo.projectdelivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.firewall.HttpStatusRequestRejectedHandler;

@Configuration
class DeliverySecurityConfig {
    @Bean @Order(1) SecurityFilterChain deliveryPreviewChain(HttpSecurity http, DeliveryAccess access,
            DeliveryPreview preview, ObjectMapper json) throws Exception {
        return http.securityMatcher(request -> request.getRequestURI().startsWith(DeliveryGrants.PREFIX))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable).csrf(AbstractHttpConfigurer::disable)
                .headers(AbstractHttpConfigurer::disable).cors(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable).anonymous(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterAfter(new DeliveryPreviewFilter(access, preview, json), AuthorizationFilter.class).build();
    }

    @Bean WebSecurityCustomizer deliveryRejectedRequests(ObjectMapper json) {
        var fallback = new HttpStatusRequestRejectedHandler();
        return web -> web.requestRejectedHandler((request, response, failure) -> {
            if (!request.getRequestURI().startsWith(DeliveryGrants.PREFIX)) { fallback.handle(request, response, failure); return; }
            DeliveryHttpPolicy.closed(response);
            DeliveryPreviewFilter.reject(request, response, json, DeliveryHttpPolicy.unsupported());
        });
    }
}
