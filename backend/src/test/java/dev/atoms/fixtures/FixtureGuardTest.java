package dev.atoms.fixtures;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FixtureGuardTest {
    @Test void rejectsApplicationDatabaseRoleAndPublicBinding() {
        assertThatCode(() -> FixtureConfiguration.requireTestDatabase("atoms_demo_test", "atoms_test", "127.0.0.1")).doesNotThrowAnyException();
        assertThatThrownBy(() -> FixtureConfiguration.requireTestDatabase("atoms_demo", "atoms_test", "127.0.0.1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> FixtureConfiguration.requireTestDatabase("atoms_demo_test", "atoms_app", "127.0.0.1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> FixtureConfiguration.requireTestDatabase("atoms_demo_test", "atoms_test", "0.0.0.0")).isInstanceOf(IllegalStateException.class);
    }
}
