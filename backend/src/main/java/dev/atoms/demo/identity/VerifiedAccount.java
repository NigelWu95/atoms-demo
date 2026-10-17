package dev.atoms.demo.identity;

import java.util.UUID;

/** Version is captured in the credential verification transaction, never refreshed afterwards. */
record VerifiedAccount(UUID userId, long version) {}
