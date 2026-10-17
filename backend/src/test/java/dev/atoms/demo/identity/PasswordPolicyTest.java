package dev.atoms.demo.identity;

import dev.atoms.demo.common.ApiException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PasswordPolicyTest {
    @Test void rejectsExcessConcurrentWorkAndReleasesSlotsAfterFailure() throws Exception {
        var policy=new PasswordPolicy();
        var entered=new java.util.concurrent.CountDownLatch(2); var release=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> work=()->policy.bounded(()->{
                entered.countDown();
                try { if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                return true;
            });
            var first=pool.submit(work); var second=pool.submit(work);
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->policy.bounded(()->true)).isInstanceOf(AuthRateLimited.class);
            release.countDown(); assertThat(first.get()).isTrue(); assertThat(second.get()).isTrue();
            assertThatThrownBy(()->policy.bounded(()->{ throw new IllegalStateException("test failure"); })).isInstanceOf(IllegalStateException.class);
            assertThat(policy.bounded(()->true)).isTrue();
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void enforcesCodepointsAndBcryptByteLimitWithoutTrimming() {
        assertThat(PasswordPolicy.validate("  long passphrase  ")).isEqualTo("  long passphrase  ");
        assertThat(PasswordPolicy.validate("a".repeat(64))).hasSize(64);
        assertThat(PasswordPolicy.validate("界".repeat(24))).hasSize(24);
        for (String invalid : new String[]{"short", "a".repeat(65), "界".repeat(25), "x".repeat(15) + '\0', "x".repeat(15) + '\uD800'})
            assertThatThrownBy(() -> PasswordPolicy.validate(invalid)).isInstanceOf(ApiException.class);
    }
    @Test void usesSaltedCostTwelveHashesAndRejectsUnknownFormats() {
        var policy = new PasswordPolicy();
        String password = "test-only-long-passphrase";
        String first = policy.encode(password), second = policy.encode(password);
        assertThat(first).startsWith("{bcrypt}$2a$12$").isNotEqualTo(second).doesNotContain(password);
        assertThat(policy.matches(password, first)).isTrue();
        assertThat(policy.matches("another-long-passphrase", first)).isFalse();
        assertThat(policy.matches(password, "{noop}" + password)).isFalse();
    }
}
