package no.syntakserror.lyngvi.core.contract;

import java.util.List;
import no.syntakserror.lyngvi.core.doctor.Check;

/** Result of {@code doctor}: every check plus counts per level. */
public record DoctorReport(List<Check> checks, int ok, int warn, int fail) {

    public static DoctorReport of(List<Check> checks) {
        int ok = 0, warn = 0, fail = 0;
        for (Check c : checks) {
            switch (c.level()) {
                case OK -> ok++;
                case WARN -> warn++;
                case FAIL -> fail++;
            }
        }
        return new DoctorReport(List.copyOf(checks), ok, warn, fail);
    }

    /** True when nothing failed; warnings do not make a sandbox unhealthy. */
    public boolean healthy() {
        return fail == 0;
    }
}
