package dev.atoms.standalone;

import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import com.fasterxml.jackson.databind.JsonNode;

@RestController
final class StateController {
    private final GuestIdentityService guests;
    private final AppStateService state;
    StateController(GuestIdentityService guests, AppStateService state) { this.guests = guests; this.state = state; }

    @GetMapping("/api/state")
    StateDocument get(Principal principal) {
        return state.get(UUID.fromString(guests.get(principal.getName()).userId()));
    }

    @PutMapping("/api/state")
    StateDocument put(Principal principal, @RequestBody JsonNode body,
                      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        UUID guest = UUID.fromString(guests.get(principal.getName()).userId());
        return state.save(guest, StateWriteRequest.parse(body, key));
    }
}
