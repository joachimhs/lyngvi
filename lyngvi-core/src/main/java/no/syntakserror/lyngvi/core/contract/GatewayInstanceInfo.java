package no.syntakserror.lyngvi.core.contract;

import java.util.List;

/**
 * One gateway instance as the host sees it. {@code status} is {@code no-secret}, {@code no-allowlist},
 * {@code no-plugin} or {@code ready}; the running gateway's own view comes from {@code gateway status}.
 */
public record GatewayInstanceInfo(String id, String plugin, String label, String hostname, List<String> hostAliases,
                                  String baseUrlEnv, String secretEnv, String secretScheme, String allowlistFile,
                                  String allowlistLabel, String allowlistMode, int allowlistEntries, boolean secretPresent,
                                  List<String> capabilities, String urlEnvName, String status) {}
