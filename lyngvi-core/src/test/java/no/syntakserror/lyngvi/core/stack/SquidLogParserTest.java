package no.syntakserror.lyngvi.core.stack;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SquidLogParserTest {

    @Test
    void parsesADeniedConnect() {
        var r = SquidLogParser.parse("1757000000.123    145 172.31.240.20 TCP_DENIED/403 3941 CONNECT example.com:443 - HIER_NONE/- text/html");

        assertThat(r).isPresent();
        assertThat(r.get().host()).isEqualTo("example.com");
        assertThat(r.get().status()).isEqualTo(403);
        assertThat(r.get().method()).isEqualTo("CONNECT");
        assertThat(r.get().at()).isEqualTo(1757000000123L);
        assertThat(r.get().url()).isEqualTo("example.com:443");
    }

    @Test
    void parsesADeniedPlainHttpUrl() {
        var r = SquidLogParser.parse("1757000001.000 12 172.31.240.20 TCP_DENIED/403 3800 GET http://evil.example.net/x/y?z=1 - HIER_NONE/- text/html");

        assertThat(r).isPresent();
        assertThat(r.get().host()).isEqualTo("evil.example.net");
        assertThat(r.get().method()).isEqualTo("GET");
    }

    @Test
    void ignoresAllowedRequestsAndGarbage() {
        assertThat(SquidLogParser.parse("1757000002.000 300 172.31.240.20 TCP_TUNNEL/200 5000 CONNECT registry.npmjs.org:443 - HIER_DIRECT/1.2.3.4 -")).isEmpty();
        assertThat(SquidLogParser.parse("")).isEmpty();
        assertThat(SquidLogParser.parse("2026/09/04 12:00:00| Squid starting")).isEmpty();
    }

    @Test
    void aggregatesByHost() {
        List<String> lines = List.of(
                "1757000000.000 1 172.31.240.20 TCP_DENIED/403 1 CONNECT a.example:443 - HIER_NONE/- -",
                "1757000005.000 1 172.31.240.20 TCP_DENIED/403 1 CONNECT b.example:443 - HIER_NONE/- -",
                "1757000009.000 1 172.31.240.20 TCP_DENIED/403 1 CONNECT a.example:443 - HIER_NONE/- -");
        BlockedRequests agg = new BlockedRequests();
        lines.forEach(l -> SquidLogParser.parse(l).ifPresent(agg::add));

        List<BlockedRequest> snapshot = agg.snapshot();

        assertThat(snapshot).extracting("host").containsExactly("a.example", "b.example");
        assertThat(snapshot.getFirst().count()).isEqualTo(2);
        assertThat(snapshot.getFirst().firstSeen()).isEqualTo(1757000000000L);
        assertThat(snapshot.getFirst().lastSeen()).isEqualTo(1757000009000L);
    }
}
