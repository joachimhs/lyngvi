package no.syntakserror.lyngvi.core.contract;

/**
 * One file in a reviewed patch (spec §5.6).
 *
 * @param change      {@code added}, {@code modified}, {@code deleted} or {@code renamed}
 * @param bucket      {@code apply}, {@code hold} or {@code reject}
 * @param reason      why it is held or rejected; null for {@code apply}
 * @param mode        the new file mode when the patch sets one, e.g. {@code 100755}
 * @param conflict    true when {@code git apply --check} fails against the host repository
 */
public record FileChange(String path, String renamedFrom, String change, String bucket, String reason,
                         int additions, int deletions, boolean binary, String mode, boolean conflict) {

    public FileChange withConflict(boolean c) {
        return new FileChange(path, renamedFrom, change, bucket, reason, additions, deletions, binary, mode, c);
    }
}
