package no.syntakserror.lyngvi.core.stack;

import java.util.List;

/** Result of {@code status} for the shared stack and of {@code up}/{@code down}. */
public record StackStatus(String prefix, List<Service> services) {

    /** One service of the stack; {@code state} is empty when no container exists. */
    public record Service(String name, String container, String state, String health) {}
}
