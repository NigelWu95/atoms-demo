package dev.atoms.demo.agent;

import dev.atoms.demo.project.ProjectService;
import dev.atoms.demo.source.ProjectSourceEditGateway;
import dev.atoms.demo.source.SourceTree;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Explicit first step only. Callers submit a requirement separately, bound to the committed initial version. */
@Service
public class ProjectAgentInitialization {
    private final ProjectAgentService tasks;
    private final ProjectService projects;
    private final ProjectSourceEditGateway source;
    private final Supplier<SourceTree> template;
    private final dev.atoms.demo.artifact.ArtifactService artifacts;

    @org.springframework.beans.factory.annotation.Autowired
    ProjectAgentInitialization(ProjectAgentService tasks, ProjectService projects, ProjectSourceEditGateway source,
            dev.atoms.demo.artifact.ArtifactService artifacts) {
        this.tasks = tasks; this.projects = projects; this.source = source;
        this.template = new FullProjectTemplateCatalog()::load; this.artifacts = artifacts;
    }
    ProjectAgentInitialization(ProjectAgentService tasks, ProjectService projects, ProjectSourceEditGateway source, Supplier<SourceTree> template) {
        this.tasks = tasks; this.projects = projects; this.source = source; this.template = template; this.artifacts = null;
    }
    ProjectAgentInitialization(ProjectAgentService tasks, ProjectService projects, ProjectSourceEditGateway source) {
        this(tasks, projects, source, new FullProjectTemplateCatalog()::load);
    }

    public ProjectSourceEditGateway.Submitted initialize(UUID owner, UUID project, String key) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Initialization requires no outer transaction");
        tasks.authorizeMaintainer(owner);
        var existing = projects.get(owner, project.toString());
        if (existing.archived()) throw AgentTask.conflict("AGENT_PROJECT_ARCHIVED");
        var tree = template.get();
        if (artifacts != null && existing.currentVersionId() != null) {
            tree = LegacyEngineeringSource.attach(tree, artifacts.get(owner, project.toString(), existing.currentVersionId().toString()));
            return source.importTemplate(owner, project, tree, existing.currentVersionId(), FullProjectTemplateCatalog.ID, tree.sha256(), key);
        }
        return source.initializeTemplate(owner, project, tree, FullProjectTemplateCatalog.ID, tree.sha256(), key);
    }
}
