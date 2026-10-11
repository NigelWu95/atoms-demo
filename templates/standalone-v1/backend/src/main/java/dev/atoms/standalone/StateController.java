package dev.atoms.standalone;

import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class StateController {
    private final GuestIdentityService guests;
    private final AppStateService state;
    StateController(GuestIdentityService guests, AppStateService state) { this.guests = guests; this.state = state; }

    @GetMapping("/api/state")
    StateDocument get(Principal principal) {
        return state.get(UUID.fromString(guests.get(principal.getName()).userId()));
    }
}
