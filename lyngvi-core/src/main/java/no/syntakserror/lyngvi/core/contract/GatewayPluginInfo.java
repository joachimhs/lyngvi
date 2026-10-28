package no.syntakserror.lyngvi.core.contract;

/** One plugin type the gateway can serve: the built-in {@code http} or a jar in {@code gateway/plugins/}. {@code error} is set for a jar that cannot be loaded. */
public record GatewayPluginInfo(String type, String label, String allowlistLabel, int apiVersion, String source, String error) {}
