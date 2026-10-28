package no.syntakserror.lyngvi.core.ask;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The adapter's {@code usage} JSON becomes quotas the front-ends can draw (spec §6.2). */
class AgentUsageTest {

    @Test
    void parsesPlanLimitsAndKeepsUnknownMembers() {
        AgentUsage u = AgentUsage.parse(Map.of(
                "supported", true,
                "plan", "Pro",
                "resetAt", "2026-09-05T10:00:00Z",
                "limits", List.of(Map.of("id", "weekly", "label", "Weekly", "used", 380, "limit", 400, "unit", "requests",
                        "resetAt", "2026-09-08T10:00:00Z")),
                "seat", "team-a"));

        assertThat(u.supported()).isTrue();
        assertThat(u.plan()).isEqualTo("Pro");
        assertThat(u.resetAt()).isEqualTo(Instant.parse("2026-09-05T10:00:00Z").toEpochMilli());
        assertThat(u.limits()).hasSize(1);
        UsageLimit weekly = u.limits().getFirst();
        assertThat(weekly.label()).isEqualTo("Weekly");
        assertThat(weekly.ratio()).hasValue(0.95);
        assertThat(weekly.level()).isEqualTo(UsageLimit.Level.DANGER);
        assertThat(u.extra()).containsExactly(Map.entry("seat", "team-a"));
    }

    @Test
    void aLimitWithoutACeilingIsARunningTotal() {
        AgentUsage u = AgentUsage.parse(Map.of("limits", List.of(Map.of("label", "Tokens", "used", 1234))));

        UsageLimit tokens = u.limits().getFirst();
        assertThat(u.supported()).isTrue();
        assertThat(tokens.id()).isEqualTo("Tokens");
        assertThat(tokens.limit()).isNull();
        assertThat(tokens.ratio()).isEmpty();
        assertThat(tokens.level()).isEqualTo(UsageLimit.Level.OK);
    }

    @Test
    void warnsAtThreeQuartersAndIgnoresMalformedEntries() {
        AgentUsage u = AgentUsage.parse(Map.of("limits", List.of(
                Map.of("id", "a", "used", "30", "limit", "40"),
                Map.of("used", 5))));

        assertThat(u.limits()).hasSize(1);
        assertThat(u.limits().getFirst().level()).isEqualTo(UsageLimit.Level.WARN);
    }

    @Test
    void amountsKeepWholeCountsWholeAndFractionalCreditsExact() {
        assertThat(UsageLimit.format(1500)).isEqualTo("1,500");
        assertThat(UsageLimit.format(0)).isEqualTo("0");
        assertThat(UsageLimit.format(12.5)).isEqualTo("12.50");
    }

    @Test
    void unsupportedIsTheDefaultAndCanCarryAReason() {
        assertThat(AgentUsage.parse(Map.of("supported", false)).supported()).isFalse();
        assertThat(AgentUsage.parse(Map.of()).supported()).isFalse();
        assertThat(AgentUsage.parse(null).limits()).isEmpty();
        assertThat(AgentUsage.unsupported("no adapter action").note()).isEqualTo("no adapter action");
    }
}
