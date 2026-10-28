package no.syntakserror.lyngvi.gateway.api;

/** Masked logging: secrets and Authorization values never appear. */
public interface Log {
    void info(String message);
    void warn(String message);
    void error(String message, Throwable t);
}
