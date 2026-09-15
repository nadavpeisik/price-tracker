package com.np.pricehunt.backend.service;

/** What provisioning did for the caller (issue #249). */
public enum AdmissionOutcome {
    /** An account was created for this identity. */
    PROVISIONED,
    /** The identity already had one; nothing changed. */
    ALREADY_ADMITTED
}
