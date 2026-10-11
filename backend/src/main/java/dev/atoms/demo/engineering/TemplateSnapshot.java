package dev.atoms.demo.engineering;

import java.util.List;
import java.util.Set;

/** Only the trusted template catalog (and package tests) can construct a snapshot. */
public final class TemplateSnapshot {
    public static final String ID = "vue-spring-postgres";
    public static final String VERSION = "1.0.0";
    private final List<EngineeringBundle.File> files;

    TemplateSnapshot(Set<String> allowedPaths, List<EngineeringBundle.File> files) {
        this.files = List.copyOf(files);
        TemplatePolicy.validate(Set.copyOf(allowedPaths), this.files);
    }
    public List<EngineeringBundle.File> files() { return files; }
}
