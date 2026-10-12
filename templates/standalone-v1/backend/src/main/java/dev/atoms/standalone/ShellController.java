package dev.atoms.standalone;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serve the verified immutable classpath snapshot, never arbitrary paths or a SPA API fallback. */
@RestController
final class ShellController {
    static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; "
            + "img-src 'self'; font-src 'self'; frame-src 'self'; object-src 'none'; base-uri 'none'; "
            + "form-action 'none'; frame-ancestors 'none'";
    private final RuntimeResources resources;
    ShellController(RuntimeResources resources) { this.resources = resources; }

    static boolean publicShell(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return request.getMethod().equals("GET") && (path.equals("/") || path.equals("/index.html")
                || path.equals("/favicon.svg") || path.matches("/assets/[a-zA-Z0-9_-]+\\.(js|css)"));
    }

    // Explicit MVC mapping takes precedence over Boot's generic static-resource handler.
    // https://docs.spring.io/spring-boot/3.5/reference/web/servlet.html#web.servlet.spring-mvc.static-content
    @GetMapping({"/", "/index.html", "/favicon.svg", "/assets/{name}"})
    ResponseEntity<byte[]> shell(HttpServletRequest request, HttpServletResponse response) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.equals("/")) path = "/index.html";
        byte[] bytes = resources.shellResource("static" + path);
        if (bytes == null) throw new ApiException(ApiException.Code.NOT_FOUND);
        String type = path.endsWith(".html") ? "text/html;charset=UTF-8" : path.endsWith(".js")
                ? "text/javascript;charset=UTF-8" : path.endsWith(".css") ? "text/css;charset=UTF-8" : "image/svg+xml";
        // Replace the deny-by-default header, do not append a second (intersecting) CSP policy.
        response.setHeader("Content-Security-Policy", CSP);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).body(bytes);
    }
}
