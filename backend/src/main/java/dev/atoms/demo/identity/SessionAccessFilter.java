package dev.atoms.demo.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.common.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Security-chain only, deliberately not registered as a second servlet filter. */
final class SessionAccessFilter extends OncePerRequestFilter {
    private final SessionAccess access;
    private final ObjectMapper json;
    SessionAccessFilter(SessionAccess access, ObjectMapper json) { this.access = access; this.json = json; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            var session = request.getSession(false);
            boolean valid;
            try { valid = session != null && access.valid(session.getId(), auth); }
            catch (DataAccessException unavailable) {
                reject(request, response, 503, "SESSION_UNAVAILABLE", "暂时无法验证会话，请稍后重试。"); return;
            }
            boolean user = auth.getAuthorities().stream().anyMatch(role -> role.getAuthority().equals("ROLE_USER"));
            if (!valid || (user && !EmailAuthController.secure(request))) {
                SecurityContextHolder.clearContext();
                if (session != null) session.invalidate();
                reject(request, response, 401, "SESSION_REQUIRED", "会话已失效，请重新连接并登录。"); return;
            }
        }
        chain.doFilter(request, response);
    }
    private void reject(HttpServletRequest request, HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        json.writeValue(response.getOutputStream(), ApiError.of(request, code, message, status == 503));
    }
}
