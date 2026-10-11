package dev.atoms.standalone;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static dev.atoms.standalone.StateHttpFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

class StateReadIT {
    private StateHttpFixture app;
    @BeforeEach void setup() { app = new StateHttpFixture(); }
    @AfterEach void stop() { if (app != null) app.close(); }

    @Test void firstReadUsesInitialStateWithoutCreatingOrSharingStoredValues() throws Exception {
        var a = app.new Browser().initialize();
        var b = app.new Browser().initialize();
        var initial = JSON.readTree("{\"value\":{\"count\":0},\"revision\":0,\"stateSchemaVersion\":1}");
        assertThat(a.get()).isEqualTo(initial);
        assertThat(b.get()).isEqualTo(initial);
        assertThat(app.jdbc().queryForObject("SELECT count(*) FROM standalone_app.app_state", Integer.class)).isZero();
        app.jdbc().update("INSERT INTO standalone_app.app_state(guest_id, value, revision, state_schema_version) VALUES (?, ?::json, 3, 1)",
                a.user, "{\"count\":7,\"nested\":{\"note\":\"private\"}}");
        assertThat(a.get().path("revision").asInt()).isEqualTo(3);
        assertThat(a.get().path("value").path("count").asInt()).isEqualTo(7);
        assertThat(b.get()).isEqualTo(initial);
        // Query parameters never select a different visitor or reset persisted data.
        assertThat(body(b.send("GET", "/api/state?guestId=" + a.user, null, null))).isEqualTo(initial);
        assertThat(a.get().path("value").path("nested").path("note").asText()).isEqualTo("private");
    }

    @Test void requiresAnExistingAuthenticatedVisitor() throws Exception {
        var browser = app.new Browser();
        error(browser.send("GET", "/api/state", null, null), 401, "SESSION_REQUIRED");
        browser.initialize();
        app.jdbc().update("DELETE FROM standalone_app.guest_users WHERE id = ?", browser.user);
        error(browser.send("GET", "/api/state", null, null), 401, "SESSION_REQUIRED");
    }

    @Test void databaseFailureIsNotAnEmptyInitialState() throws Exception {
        var browser = app.new Browser().initialize();
        app.context.getBean(HikariDataSource.class).close();
        error(browser.send("GET", "/api/state", null, null), 503, "SERVICE_UNAVAILABLE");
    }
}
