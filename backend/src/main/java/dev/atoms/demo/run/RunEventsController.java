package dev.atoms.demo.run;

import dev.atoms.demo.common.ApiException;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class RunEventsController {
    private final RunRepository runs;
    private final RunEventStreams streams;
    public RunEventsController(RunRepository runs, RunEventStreams streams) { this.runs = runs; this.streams = streams; }

    @GetMapping("/api/runs/{id}/events")
    ResponseEntity<SseEmitter> events(Principal principal, @PathVariable String id,
            @RequestParam(required = false) String after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        Run run = runs.get(UUID.fromString(principal.getName()), id);
        long cursor = cursor(lastEventId == null || lastEventId.isEmpty() ? after : lastEventId, run.lastEventSeq());
        if (!run.active() && cursor == run.lastEventSeq()) return ResponseEntity.noContent().build();
        runs.events(run, cursor); // Fail before starting the response if history has a gap.
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("X-Accel-Buffering", "no")
                .contentType(org.springframework.http.MediaType.TEXT_EVENT_STREAM).body(streams.open(run, cursor));
    }
    private long cursor(String value, long last) {
        if (value == null) return 0;
        try {
            long cursor = Long.parseLong(value);
            if (cursor < 0 || cursor > 9007199254740991L || cursor > last || !Long.toString(cursor).equals(value)) throw new NumberFormatException();
            return cursor;
        } catch (NumberFormatException invalid) { throw new ApiException(400, "INVALID_CURSOR", "任务事件位置无效，请刷新任务快照。"); }
    }
}
