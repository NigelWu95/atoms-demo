package dev.atoms.demo.workspace;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BuildFixtureTest {
    @Test void refusesMissingRelativeOrAdditionalArgumentsBeforeAnyStartup() {
        for(String[] args:new String[][]{{},{"relative","/node","/java","/test.properties"},{"/root","/node","/java","/test.properties","--skip"}})
            assertThatThrownBy(()->BuildFixture.arguments(args)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void explicitSettingsCannotChangeTheDatabaseOrListener() {
        String[] args=BuildFixture.arguments(new String[]{"/private/tmp/builds","/opt/node","/opt/java","/private/tmp/test.properties"});
        assertThat(args).contains("--server.address=127.0.0.1","--server.port=8081","--atoms.build.enabled=true","--atoms.generation.worker-enabled=false");
        assertThat(java.util.Arrays.stream(args)).noneMatch(arg->arg.startsWith("--spring.datasource"));
    }
}
