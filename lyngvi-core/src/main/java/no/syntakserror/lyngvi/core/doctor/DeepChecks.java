package no.syntakserror.lyngvi.core.doctor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.api.StackService;
import no.syntakserror.lyngvi.core.runtime.Runner;

/**
 * {@code doctor --deep}: an egress self-test from a throwaway container on the internal network (spec M12):
 * an allowed domain answers through the proxy, a denied one does not, a direct connection fails, the gateway answers.
 */
public final class DeepChecks {

    private DeepChecks() {}

    public static List<Check> run(Sandbox sandbox) {
        List<Check> out = new ArrayList<>();
        String prefix = sandbox.config().subnetPrefix().orElse(null);
        if (prefix == null) return List.of(Check.warn("egress", "deep checks skipped: no subnet allocated", "Run 'setup.sh rebuild-config'"));
        if (!sandbox.stackService().baseImageExists()) {
            return List.of(Check.warn("egress", "deep checks skipped: " + StackService.baseImage() + " is not built", "Run 'setup.sh build'"));
        }
        boolean proxyUp = sandbox.stackService().status().services().stream().anyMatch(s -> s.name().equals("proxy") && s.state().equals("running"));
        if (!proxyUp) return List.of(Check.warn("egress", "deep checks skipped: the proxy is not running", "Run 'setup.sh up'"));
        List<String> allow = sandbox.profile().egress().allow();
        String allowed = allow.isEmpty() ? null : allow.getFirst().replaceAll("^\\.", "www.");
        String proxy = "http://" + prefix + ".5:3128";
        String gateway = "http://" + prefix + ".10:8080/healthz";
        String script = "a=$(curl -s -m 15 -o /dev/null -w '%{http_code}' -x " + proxy + " https://" + (allowed == null ? "example.invalid" : allowed) + "/ 2>/dev/null); "
                + "d=$(curl -s -m 10 -o /dev/null -w '%{http_code}' -x " + proxy + " https://example.invalid/ 2>/dev/null); "
                + "x=$(curl -s -m 5 -o /dev/null -w '%{http_code}' https://" + (allowed == null ? "example.invalid" : allowed) + "/ 2>/dev/null); "
                + "g=$(curl -s -m 10 -o /dev/null -w '%{http_code}' " + gateway + " 2>/dev/null); "
                + "echo \"allowed=$a denied=$d direct=$x gateway=$g\"";
        List<String> cmd = new ArrayList<>(List.of("podman", "run", "--rm", "--network", sandbox.paths().projectName() + "_internal",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--entrypoint", "sh", StackService.baseImage(), "-c", script));
        Runner.Result r;
        try {
            r = sandbox.runner().run(cmd, sandbox.paths().root(), Duration.ofMinutes(2));
        } catch (SandboxException e) {
            return List.of(Check.fail("egress", "the self-test container could not start: " + e.getMessage(), "Check 'podman run' and the stack"));
        }
        Map<String, String> values = parse(r.stdout());
        if (values.isEmpty()) {
            return List.of(Check.fail("egress", "the self-test produced no result: " + r.stderr().strip(), "Run 'setup.sh doctor --deep' again after 'setup.sh up'"));
        }
        String a = values.getOrDefault("allowed", "000");
        if (allowed == null) out.add(Check.warn("egress-allowed", "no domain in profile.egress.allow to test", ""));
        else if (a.startsWith("2") || a.startsWith("3") || a.equals("401") || a.equals("404")) out.add(Check.ok("egress-allowed", allowed + " reachable through the proxy (" + a + ")"));
        else out.add(Check.fail("egress-allowed", allowed + " is not reachable through the proxy (" + a + ")", "Check the proxy log with 'setup.sh logs proxy'"));
        String d = values.getOrDefault("denied", "000");
        out.add(d.equals("000") || d.equals("403") ? Check.ok("egress-denied", "a host outside the allowlist is denied")
                : Check.fail("egress-denied", "a host outside the allowlist answered " + d, "Check proxy/squid.conf and the allowlist"));
        String x = values.getOrDefault("direct", "000");
        out.add(x.equals("000") ? Check.ok("egress-direct", "no direct internet access from the internal network")
                : Check.fail("egress-direct", "direct access bypassed the proxy (" + x + ")", "The internal network must be internal: true"));
        String g = values.getOrDefault("gateway", "000");
        out.add(g.equals("200") ? Check.ok("egress-gateway", "the gateway answers on the internal network")
                : Check.warn("egress-gateway", "the gateway did not answer (" + g + ")", "Run 'setup.sh gateway status'"));
        return out;
    }

    static Map<String, String> parse(String stdout) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String line : stdout.lines().toList()) {
            if (!line.contains("allowed=")) continue;
            for (String part : line.strip().split("\\s+")) {
                int eq = part.indexOf('=');
                if (eq > 0) out.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        return out;
    }
}
