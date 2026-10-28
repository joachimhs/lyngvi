package no.syntakserror.lyngvi.core.contract;

/** Whether an instance's secret file exists and what it holds, with the token masked to its last four characters. */
public record SecretStatus(String id, boolean present, String file, String baseUrl, String masked) {}
