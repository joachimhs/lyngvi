package no.syntakserror.lyngvi.gateway.api;

/**
 * @param file  file under {@code gateway/allowlists/}
 * @param mode  for the {@code http} plugin: {@code none}, {@code path-prefix}, {@code path-exact}, {@code path-regex}, {@code query:<param>}; null for code plugins
 * @param label noun for entries
 */
public record AllowlistSpec(String file, String mode, String label) {}
