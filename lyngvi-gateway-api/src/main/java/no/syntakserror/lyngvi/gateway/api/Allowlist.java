package no.syntakserror.lyngvi.gateway.api;

/** The instance's allowlist as the runtime enforces it. */
public interface Allowlist {

    /** True when enforcement is off or the key is covered. Null keys are never allowed. */
    boolean allows(String key);

    boolean enforced();

    AllowlistInfo describe();

    /** Throws 403 when the key is not allowed. */
    default void require(String key, String what) {
        if (!allows(key)) {
            throw new GatewayException(403, what + " not on allowlist", java.util.Map.of("key", String.valueOf(key),
                    "hint", "allow it on the host with: lyngvi gateway allow <instance> add " + key));
        }
    }
}
