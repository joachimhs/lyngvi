package no.syntakserror.lyngvi.core.stack;

import static org.assertj.core.api.Assertions.assertThat;

import no.syntakserror.lyngvi.core.config.ResourceLimits;
import no.syntakserror.lyngvi.core.config.ResourcePreset;
import org.junit.jupiter.api.Test;

class ResourceFitTest {

    private static final MachineInfo MACHINE = new MachineInfo("podman-machine-default", "running", 8, 26L << 30, 93L << 30, true);

    @Test
    void countsHowManySessionsTheMachineHoldsAtOnce() {
        assertThat(ResourceFit.sessions(new ResourceLimits("8g", "12g", "4.0", "2048"), MACHINE)).isEqualTo(3);
        assertThat(ResourceFit.sessions(new ResourceLimits("2g", "3g", "1.0", "1024"), MACHINE)).isEqualTo(13);
        // CPU is time-shared, so asking for every core does not change how many fit.
        assertThat(ResourceFit.sessions(new ResourceLimits("8g", "12g", "8.0", "2048"), MACHINE)).isEqualTo(3);
    }

    @Test
    void anUninspectableMachineOrAnUnparsableSizeIsUnknownRatherThanZero() {
        assertThat(ResourceFit.sessions(new ResourceLimits("8g", "12g", "4.0", "2048"), MachineInfo.unavailable())).isEqualTo(ResourceFit.UNKNOWN);
        assertThat(ResourceFit.sessions(new ResourceLimits("lots", "12g", "4.0", "2048"), MACHINE)).isEqualTo(ResourceFit.UNKNOWN);
        assertThat(ResourceFit.fits(new ResourceLimits("64g", "96g", "4.0", "2048"), MachineInfo.unavailable())).isTrue();
    }

    @Test
    void limitsLargerThanTheMachineDoNotFit() {
        assertThat(ResourceFit.fits(new ResourceLimits("16g", "24g", "6.0", "4096"), MACHINE)).isTrue();
        assertThat(ResourceFit.fits(new ResourceLimits("32g", "48g", "6.0", "4096"), MACHINE)).isFalse();
        assertThat(ResourceFit.fits(new ResourceLimits("8g", "12g", "16.0", "2048"), MACHINE)).isFalse();
    }

    @Test
    void theMachinePresetTakesThreeQuartersOfTheMemoryAndEveryCore() {
        ResourcePreset p = ResourceFit.machineMax(MACHINE).orElseThrow();

        assertThat(p.id()).isEqualTo("machine");
        assertThat(p.limits()).isEqualTo(new ResourceLimits("19g", "28g", "8.0", "4096"));
        assertThat(ResourceFit.fits(p.limits(), MACHINE)).isTrue();

        assertThat(ResourceFit.machineMax(MachineInfo.unavailable())).isEmpty();
        // A machine too small to give three quarters of a GiB still gets a usable preset.
        assertThat(ResourceFit.machineMax(new MachineInfo("tiny", "running", 1, 512L << 20, 1L << 30, true)).orElseThrow().limits())
                .isEqualTo(new ResourceLimits("1g", "2g", "1.0", "4096"));
    }
}
