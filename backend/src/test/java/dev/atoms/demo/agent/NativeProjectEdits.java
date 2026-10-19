package dev.atoms.demo.agent;

import java.nio.file.Files;
import java.nio.file.Path;

/** Deterministic source edits for both the child process and the loopback model; not a runnable app claim. */
final class NativeProjectEdits {
    static final String VUE = "frontend/src/App.vue";
    static final String JAVA = "backend/src/main/java/dev/atoms/generated/JourneyController.java";
    static final String FIRST_TITLE = "第一轮：任务列表";
    static final String SECOND_TITLE = "第二轮：增加任务筛选";
    static String before(int round) { return round == 1 ? "从这里，开始构建。" : FIRST_TITLE; }
    static String after(int round) { return round == 1 ? FIRST_TITLE : SECOND_TITLE; }
    static String sqlPath(int round) {
        return "backend/src/main/resources/db/migration/V00" + (round + 1) + "__journey.sql";
    }
    static String javaContent(int round) {
        return "package dev.atoms.generated;\n"
                + "import org.springframework.web.bind.annotation.GetMapping;\n"
                + "import org.springframework.web.bind.annotation.RestController;\n"
                + "@RestController public class JourneyController {\n"
                + "  @GetMapping(\"/api/journey\") public String stage() { return \"round-" + round + "\"; }\n}\n";
    }
    static String sqlContent(int round) {
        return round == 1 ? "create table journey_tasks (id bigint primary key, title text not null);\n"
                : "alter table journey_tasks add column completed boolean not null default false;\n";
    }
    public static void main(String[] args) throws Exception {
        System.in.readAllBytes();
        int round = Integer.parseInt(args[1]);
        if (!args[0].equals("unchanged")) {
            String original = Files.readString(Path.of(VUE));
            if (!original.contains(before(round))) throw new IllegalStateException("Fixture baseline missing");
            Files.writeString(Path.of(VUE), original.replace(before(round), after(round)));
            Files.writeString(Path.of(JAVA), javaContent(round));
            Files.writeString(Path.of(sqlPath(round)), sqlContent(round));
        }
        System.out.println("{\"type\":\"system\",\"subtype\":\"init\",\"tools\":[\"Read\",\"Glob\",\"Grep\",\"Write\",\"Edit\"]}");
        if (!args[0].equals("incomplete"))
            System.out.println("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"permission_denials\":[]}");
    }
}
