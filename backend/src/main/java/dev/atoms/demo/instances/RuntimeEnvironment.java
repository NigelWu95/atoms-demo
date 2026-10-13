package dev.atoms.demo.instances;

import dev.atoms.demo.common.ApiException;
import dev.atoms.demo.workspace.*;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class RuntimeEnvironment {
    volatile boolean ready;
    private final Environment environment;
    private final BuildExecutionEnvironment builds;
    public RuntimeEnvironment(Environment environment, BuildExecutionEnvironment builds) { this.environment=environment;this.builds=builds; }
    record Slot(UUID id, int port, String fingerprint, Path config) {}
    record Settings(WorkspacePaths paths, BuildEnvironment.Settings tools, List<Slot> slots) {}
    boolean enabled() { return environment.getProperty("atoms.runtime.enabled",Boolean.class,false); }
    Settings admission() {
        var value=settings();
        if(!ready)throw unavailable();
        return value;
    }
    Settings settings() {
        if(!enabled()) throw new ApiException(503,"RUNTIME_DISABLED","独立运行尚未启用，请配置本机运行环境");
        try {
            var tools=builds.settings();
            var paths=new WorkspacePaths(Path.of(environment.getRequiredProperty("atoms.runtime.root")));
            if(paths.root().startsWith(tools.paths().root()) || tools.paths().root().startsWith(paths.root())) throw new IllegalArgumentException();
            String[] files=environment.getRequiredProperty("atoms.runtime.configs").split(",",-1);
            if(files.length<1 || files.length>16) throw new IllegalArgumentException();
            var slots=new ArrayList<Slot>();var ids=new HashSet<UUID>();var ports=new HashSet<Integer>();
            var databases=new HashSet<String>();var roles=new HashSet<String>();
            for(String file:files) {
                Path path=Path.of(file); if(!path.isAbsolute()) throw new IllegalArgumentException();
                byte[] bytes=BuildArtifacts.read(path,16*1024);
                var properties=new Properties();properties.load(new ByteArrayInputStream(bytes));
                var config=RuntimeConfiguration.parse(properties);
                if(!ids.add(config.id()) || !ports.add(config.port()) || !databases.add(config.databaseKey()) || !roles.add(config.role())) throw new IllegalArgumentException();
                slots.add(new Slot(config.id(),config.port(),BuildArtifacts.hash(bytes),path));
            }
            return new Settings(paths,tools,List.copyOf(slots));
        } catch(Exception error) { throw unavailable(); }
    }
    static ApiException unavailable() { return new ApiException(503,"RUNTIME_ENVIRONMENT_NOT_READY","独立运行环境未就绪，请检查专用目录、运行库及工具配置"); }
}
