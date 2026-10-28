package no.syntakserror.lyngvi.core.stack;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import no.syntakserror.lyngvi.core.contract.DiskReport;
import no.syntakserror.lyngvi.core.testing.FakeRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code podman system df -v --format json} has not kept one shape between versions, and it is the only
 * command that knows what a volume takes. The parser therefore has to read what it recognises and say
 * «unknown» — never «zero» — for the rest.
 */
class DiskSizesTest {

    private static DiskSizes read(String json, @TempDir Path tmp) {
        return DiskSizes.read(new FakeRunner().ok("podman system df", json), tmp);
    }

    @Test
    void readsNumericSizes(@TempDir Path tmp) {
        DiskSizes sizes = read("""
                {"Containers":[{"Names":["lyngvi-sb-6f1c"],"RWSize":166500000}],
                 "Volumes":[{"VolumeName":"lyngvi-sb-6f1c-work","Size":4096}]}
                """, tmp);

        assertThat(sizes.container("lyngvi-sb-6f1c")).isEqualTo(166500000);
        assertThat(sizes.volume("lyngvi-sb-6f1c-work")).isEqualTo(4096);
    }

    /** Older builds printed what a person reads, in decimal units, and sometimes a plain name. */
    @Test
    void readsHumanSizesAndTheOtherFieldNames(@TempDir Path tmp) {
        DiskSizes sizes = read("""
                {"Containers":[{"Name":"lyngvi-sb-6f1c","Size":"166.5MB"}],
                 "Volumes":[{"Name":"lyngvi-sb-6f1c-work","Size":"1.2GB"}]}
                """, tmp);

        assertThat(sizes.container("lyngvi-sb-6f1c")).isEqualTo(166_500_000L);
        assertThat(sizes.volume("lyngvi-sb-6f1c-work")).isEqualTo(1_200_000_000L);
    }

    @Test
    void anUnknownShapeGivesUnknownSizesRatherThanZero(@TempDir Path tmp) {
        for (String json : new String[] {"{\"Something\":[]}", "[]", "not json at all", ""}) {
            DiskSizes sizes = read(json, tmp);
            assertThat(sizes.container("lyngvi-sb-6f1c")).isEqualTo(DiskReport.UNKNOWN_SIZE);
            assertThat(sizes.volume("lyngvi-sb-6f1c-work")).isEqualTo(DiskReport.UNKNOWN_SIZE);
        }
    }

    @Test
    void noPodmanAtAllIsNotAFailure(@TempDir Path tmp) {
        DiskSizes sizes = DiskSizes.read(new FakeRunner(), tmp);

        assertThat(sizes.volume("anything")).isEqualTo(DiskReport.UNKNOWN_SIZE);
    }

    @Test
    void humanSizesParseInDecimalUnitsAndRefuseNonsense() {
        assertThat(ResourceSizes.parseHuman("0B")).isZero();
        assertThat(ResourceSizes.parseHuman("512kB")).isEqualTo(512_000);
        assertThat(ResourceSizes.parseHuman("1.222GB")).isEqualTo(1_222_000_000L);
        assertThat(ResourceSizes.parseHuman("")).isEqualTo(-1);
        assertThat(ResourceSizes.parseHuman("a lot")).isEqualTo(-1);
        assertThat(ResourceSizes.parseHuman(null)).isEqualTo(-1);
    }
}
