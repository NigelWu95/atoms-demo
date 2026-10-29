package dev.atoms.demo.source;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ProjectSourcePolicyTest {
    private final ProjectSourcePolicy policy = new ProjectSourcePolicy();

    @Test void acceptsFullProjectLargerThanLegacyLimitWithStableDigest() {
        var files = List.of(file("frontend/src/App.vue", "<template>Hello</template>"),
                file("backend/src/main/java/demo/App.java", "class App {}"),
                file("backend/src/main/resources/db/migration/V001__tasks.sql", "CREATE TABLE tasks(id bigint);"),
                file("frontend/package-lock.json", " ".repeat(130 * 1024)),
                file("config/application.properties.example", "spring.datasource.password=${APP_DATABASE_PASSWORD}"));
        var tree = policy.validate(files);
        assertThat(tree.files()).hasSize(5);
        assertThat(tree.totalBytes()).isGreaterThan(120 * 1024);
        var reverse = new ArrayList<>(files); java.util.Collections.reverse(reverse);
        assertThat(policy.validate(reverse).sha256()).isEqualTo(tree.sha256()).hasSize(64);
        assertThat(policy.validate(List.of(file("other.txt", "hello"))).sha256())
                .isNotEqualTo(policy.validate(List.of(file("one.txt", "hello"))).sha256());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape", "/absolute", "a/../b", "a//b", "./x", "a\\b", "a/", "a\nx", ".git/config",
            ".claude/settings.json", ".mcp.json", ".env", "backend/.env.local", "node_modules/a.js", "target/app.jar",
            "dist/index.html", "private.pem", "server.key", "logs/run.log", "backend/application.properties", "CON", "folder./file"})
    void rejectsUnsafePathsAndPrivateOrGeneratedArtifacts(String path) {
        assertThatThrownBy(() -> policy.validate(List.of(file(path, "safe")))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsCaseAliasesFileDirectoryCollisionsAndInvalidText() {
        for (var files : List.of(List.of(file("A.java", "one"), file("a.java", "two")),
                List.of(file("Frontend/a.js", "one"), file("frontend/b.js", "two")),
                List.of(file("a", "one"), file("a/b", "two")), List.of(file("a", "one"), file("a", "two")),
                List.of(new SourceTree.File("data.txt", new byte[]{(byte) 0xff}, 0100644)),
                List.of(file("image.txt", "abc\0def")))) {
            assertThatThrownBy(() -> policy.validate(files)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void countsUtf8BytesAndRejectsRatherThanTruncates() {
        var bounded = new ProjectSourcePolicy(new ProjectSourcePolicy.Limits(2, 8, 12, 20, 2));
        assertThat(bounded.validate(List.of(file("一.txt", "你好"), file("b", "123456"))).totalBytes()).isEqualTo(12);
        for (var files : List.of(List.of(file("a", "你好啊")), List.of(file("a", "12345678"), file("b", "12345")),
                List.of(file("a", "1"), file("b", "1"), file("c", "1")), List.of(file("a/b/c", "1")),
                List.of(file("名字太长名字太长名字.txt", "1")))) {
            assertThatThrownBy(() -> bounded.validate(files)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void onlyApprovedWrapperMayCarryExecutableBitAndModesAffectHash() {
        var plain = file("backend/mvnw", "#!/bin/sh\nexit 0\n");
        var executable = new SourceTree.File(plain.path(), plain.content(), 0100755);
        assertThat(policy.validate(List.of(plain)).sha256()).isNotEqualTo(policy.validate(List.of(executable)).sha256());
        for (int mode : new int[]{0120000, 0160000, 0100777, 0100755}) {
            assertThatThrownBy(() -> policy.validate(List.of(new SourceTree.File("evil.sh", new byte[0], mode))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"-----BEGIN PRIVATE KEY-----", "api_key=canary-do-not-save", "spring.datasource.password: real-password",
            "Authorization: Bearer fake-credential", "{\"apiKey\":\"fake-credential\"}"})
    void rejectsRecognizableLiteralCredentials(String content) {
        assertThatThrownBy(() -> policy.validate(List.of(file("config/config.txt", content))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(content);
    }

    @Test void allowsPlaceholderExamplesAndCopiesFileBytes() {
        byte[] content = "APP_DATABASE_PASSWORD=${APP_DATABASE_PASSWORD}".getBytes(StandardCharsets.UTF_8);
        var file = new SourceTree.File(".env.example", content, 0100644);
        content[0] = 'X';
        var tree = policy.validate(List.of(file));
        tree.files().get(0).content()[0] = 'Y';
        assertThat(new String(tree.files().get(0).content(), StandardCharsets.UTF_8)).startsWith("APP_DATABASE_PASSWORD=");
        assertThatThrownBy(() -> tree.files().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void allowsTheControlledTemplateDatabasePlaceholderButNotRealValuesWithThatPrefix() {
        assertThat(policy.validate(List.of(file("config/application.properties.example",
                "STANDALONE_DB_PASSWORD=REPLACE_WITH_DATABASE_PASSWORD"))).files()).hasSize(1);
        for (String value : List.of("REPLACE_WITH_DATABASE_PASSWORD-and-canary", "replace_with_canary-secret")) {
            assertThatThrownBy(() -> policy.validate(List.of(file("config/application.properties.example",
                    "STANDALONE_DB_PASSWORD=" + value)))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void acceptsExplicitTemplateTestPlaceholdersWithoutIgnoringOtherFieldsOnTheSameLine() {
        String fixture = "  STANDALONE_DB_PASSWORD: 'RUNTIME_CANARY', DEEPSEEK_API_KEY: 'MODEL_CANARY',\n"
                + "  STANDALONE_DB_PASSWORD: 'PRIVATE_CANARY', DEEPSEEK_API_KEY: 'PRIVATE_CANARY',";
        assertThat(policy.validate(List.of(file("scripts/process.test.mjs", fixture))).files()).hasSize(1);
        assertThat(policy.validate(List.of(file("backend/mvnw", "case \"${MVNW_PASSWORD:+has-password}\" in"))).files()).hasSize(1);
        assertThatThrownBy(() -> policy.validate(List.of(file("scripts/process.test.mjs",
                fixture.replace("'MODEL_CANARY'", "'canary-do-not-save'"))))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void acceptsPasswordParameterTypesWithoutTreatingLiteralOrConfiguredValuesAsTypes() {
        String source = "export function login(\n  username: string,\n  password: string,\n  confirmPassword: string | null,\n) {}\n";
        assertThat(policy.validate(List.of(file("frontend/src/api.ts", source))).files()).hasSize(1);
        assertThat(policy.validate(List.of(file("frontend/src/App.vue", "<script setup lang=\"ts\">\ninterface Login {\n password: string;\n}\n</script>"))).files()).hasSize(1);
        for (String value : List.of("password: 'string'", "password = string", "password: canary-do-not-save"))
            assertThatThrownBy(() -> policy.validate(List.of(file("frontend/src/api.ts", value))))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validate(List.of(file("config/application.yml", "password: string"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validate(List.of(file("frontend/src/api.ts", source + "\napiKey: 'canary-do-not-save'"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void acceptsAnExplicitBearerDocumentationPlaceholderButNotTokensOrPlaceholderPrefixes() {
        assertThat(policy.validate(List.of(file("README.md", "Use `Authorization: Bearer <token>` after login."))).files()).hasSize(1);
        assertThat(policy.validate(List.of(file("README.md", "使用 `Authorization: Bearer <token>`；不在文档中记录真实令牌。"))).files()).hasSize(1);
        for (String value : List.of("canary-do-not-save", "<token>-canary", "${APP_SESSION_TOKEN}", "<token>canary"))
            assertThatThrownBy(() -> policy.validate(List.of(file("README.md", "Authorization: Bearer " + value))))
                    .isInstanceOf(IllegalArgumentException.class);
    }

    static SourceTree.File file(String path, String value) {
        return new SourceTree.File(path, value.getBytes(StandardCharsets.UTF_8), 0100644);
    }
}
