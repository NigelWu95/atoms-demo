package dev.atoms.demo.agent;

import dev.atoms.demo.artifact.ArtifactService;
import dev.atoms.demo.source.ProjectSourcePolicy;
import dev.atoms.demo.source.SourceTree;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Source reference only; runtime records, credentials and platform sessions are never exported. */
final class LegacyEngineeringSource {
    static SourceTree attach(SourceTree template, ArtifactService.Version previous) {
        var files = new ArrayList<>(template.files());
        for (String path : List.of("view.html", "styles.css", "main.js")) {
            String value = previous.artifact().files().get(path);
            if (value == null) throw new IllegalStateException("LEGACY_SOURCE_INCOMPLETE");
            files.add(new SourceTree.File("legacy/" + path, value.getBytes(StandardCharsets.UTF_8), 0100644));
        }
        String note = """
                # Previous application source
                This folder contains the user's previous browser application as a migration reference.
                Preserve its UI and working behavior while implementing the request in frontend/ and backend/.
                Replace platform SDK state calls with real application APIs and PostgreSQL migrations when needed.
                Browser-only account simulation is not backend authentication. Implement server-side authentication
                when requested, with password hashing and server-enforced access checks.
                Existing platform runtime data remains in the previous app; no runtime records are included here.
                Do not claim automatic data migration. Do not run the legacy code as a substitute for the full app.
                """ + "\nOriginal version: " + previous.id() + " (V" + previous.versionNo() + ")\n";
        files.add(new SourceTree.File("legacy/MIGRATION.md", note.getBytes(StandardCharsets.UTF_8), 0100644));
        return new ProjectSourcePolicy().validate(files);
    }
}
