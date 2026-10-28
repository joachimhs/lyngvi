package no.syntakserror.lyngvi.gateway.api;

/**
 * Where the instance's secret comes from and how it is sent.
 *
 * @param env    environment variable holding the secret ({@code <env>_FILE} is also honoured)
 * @param scheme {@code bearer}, {@code basic}, {@code header:<Name>} or {@code query:<name>}
 */
public record SecretSpec(String env, String scheme) {}
