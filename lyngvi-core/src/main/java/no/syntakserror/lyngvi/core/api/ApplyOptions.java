package no.syntakserror.lyngvi.core.api;

import java.util.List;
import java.util.Optional;

/**
 * Arguments of {@code session apply}.
 *
 * @param mode     {@code commit} or {@code stage}
 * @param repos    repositories to deliver; empty means every repository with changes
 * @param includes files to deliver; empty means every file in the {@code apply} bucket
 * @param from     baseline: {@code start}, {@code last} or a delivery index; empty means the default
 */
public record ApplyOptions(String branch, Optional<String> message, String mode, List<String> repos, List<String> includes, Optional<String> from) {}
