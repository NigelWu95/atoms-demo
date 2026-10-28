package dev.atoms.demo.workbench;

import dev.atoms.demo.common.ApiException;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class DevelopmentModeController {
    private final Environment environment;
    DevelopmentModeController(Environment environment) { this.environment = environment; }
    record Mode(String mode) {}
    @GetMapping("/api/development-mode")
    Mode mode() {
        String value = environment.getProperty("atoms.project-workbench.default-mode", "false");
        if (!value.equals("true") && !value.equals("false"))
            throw new ApiException(503, "WORKBENCH_CONFIGURATION_INVALID", "开发模式配置无效。");
        return new Mode(value.equals("true") ? "ENGINEERING" : "LEGACY");
    }
}
