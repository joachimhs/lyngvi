package no.syntakserror.lyngvi.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.GatewayService;
import no.syntakserror.lyngvi.core.api.Sandbox;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.contract.GatewayInstanceInfo;
import no.syntakserror.lyngvi.core.contract.GatewayPluginInfo;
import no.syntakserror.lyngvi.core.contract.JsonOutput;
import no.syntakserror.lyngvi.core.contract.SecretStatus;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/** {@code lyngvi gateway list|status|logs|restart|rebuild|plugin|instance|secret|allow|cert} (spec §8.7). */
@Command(name = "gateway", description = "The gateway sidecar: instances, plugins, secrets, allowlists, certificate.",
        subcommands = {GatewayCommand.ListCommand.class, GatewayCommand.StatusCommand.class, GatewayCommand.LogsCommand.class,
                       GatewayCommand.RestartCommand.class, GatewayCommand.RebuildCommand.class, GatewayCommand.PluginCommand.class,
                       GatewayCommand.InstanceCommand.class, GatewayCommand.SecretCommand.class, GatewayCommand.AllowCommand.class,
                       GatewayCommand.CertCommand.class})
final class GatewayCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    Main main() {
        return main;
    }

    Context ctx() {
        return main.context();
    }

    @Override
    public Integer call() {
        return new ListCommand(this).call();
    }

    record InstanceList(List<GatewayInstanceInfo> instances) {}
    record PluginList(List<GatewayPluginInfo> plugins) {}
    record Entries(String id, List<String> entries) {}
    record Added(String id, String entry, boolean added) {}
    record Removed(String id, String entry, boolean removed) {}
    record Done(String action, String target) {}
    record Cert(String certificate, String hint) {}

    static void printInstance(Context ctx, GatewayInstanceInfo i) {
        ctx.out().println(i.id() + "  " + i.label() + "  plugin=" + i.plugin() + "  http://" + i.hostname()
                + (i.hostAliases().isEmpty() ? "" : " (" + String.join(", ", i.hostAliases()) + ")")
                + "  [" + i.status() + ", " + i.allowlistEntries() + " " + i.allowlistLabel() + ", secret " + (i.secretPresent() ? "set" : "missing") + "]");
    }

    @Command(name = "list", description = "Instances in the manifest with their host-side readiness.")
    static final class ListCommand implements Callable<Integer> {
        @ParentCommand private GatewayCommand parent;
        ListCommand() {}
        ListCommand(GatewayCommand parent) { this.parent = parent; }

        @Override
        public Integer call() {
            Context ctx = parent.ctx();
            List<GatewayInstanceInfo> list = ctx.open().gatewayService().list();
            if (ctx.json()) { ctx.printJson(new InstanceList(list)); return 0; }
            if (list.isEmpty()) ctx.out().println("No gateway instances. Add one with: lyngvi gateway instance add <id> --hostname <host> [--plugin <type>]");
            for (GatewayInstanceInfo i : list) printInstance(ctx, i);
            return 0;
        }
    }

    @Command(name = "status", description = "The running gateway's /healthz (instances, plugins, allowlists).")
    static final class StatusCommand implements Callable<Integer> {
        @ParentCommand private GatewayCommand parent;
        @Option(names = "--deep", description = "Also probe each upstream.") private boolean deep;

        @Override
        public Integer call() {
            Context ctx = parent.ctx();
            Map<String, Object> health = ctx.open().gatewayService().status(deep);
            if (ctx.json()) { ctx.printJson(health); return 0; }
            ctx.out().println("gateway " + health.getOrDefault("version", "?") + ": " + (Boolean.TRUE.equals(health.get("ok")) ? "ok" : "not ok"));
            Object instances = health.get("instances");
            if (instances instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Map<?, ?> m) {
                        ctx.out().println("  " + m.get("id") + "  " + m.get("plugin") + "  " + m.get("status")
                                + (m.get("upstream") == null ? "" : "  upstream=" + m.get("upstream")));
                    }
                }
            }
            Object warnings = health.get("warnings");
            if (warnings instanceof List<?> w) for (Object o : w) ctx.out().println("  warning: " + o);
            return 0;
        }
    }

    @Command(name = "logs", description = "Show or follow the gateway log.")
    static final class LogsCommand implements Callable<Integer> {
        @ParentCommand private GatewayCommand parent;
        @Option(names = {"--follow", "-f"}) private boolean follow;
        @Option(names = "--tail", paramLabel = "<n>") private int tail = 100;

        @Override
        public Integer call() {
            Context ctx = parent.ctx();
            ctx.open().gatewayService().logs(follow, tail, line -> {
                if (ctx.json()) ctx.out().println(JsonOutput.line(Map.of("ts", System.currentTimeMillis(), "service", "gateway", "line", line)));
                else ctx.out().println(line);
            });
            return 0;
        }
    }

    @Command(name = "restart", description = "Restart the gateway container (re-reads manifest, secrets and certificate).")
    static final class RestartCommand implements Callable<Integer> {
        @ParentCommand private GatewayCommand parent;

        @Override
        public Integer call() {
            Context ctx = parent.ctx();
            ctx.open().gatewayService().restart();
            if (ctx.json()) ctx.printJson(new Done("restart", "gateway")); else ctx.out().println("gateway restarted");
            return 0;
        }
    }

    @Command(name = "rebuild", description = "Rebuild the gateway image and recreate the container.")
    static final class RebuildCommand implements Callable<Integer> {
        @ParentCommand private GatewayCommand parent;
        @Option(names = "--no-cache") private boolean noCache;

        @Override
        public Integer call() {
            Context ctx = parent.ctx();
            ctx.open().gatewayService().rebuild(noCache, line -> {
                if (ctx.json()) ctx.out().println(JsonOutput.line(Map.of("type", "log", "line", line))); else ctx.out().println(line);
            });
            if (ctx.json()) ctx.printJson(new Done("rebuild", "gateway")); else ctx.out().println("gateway rebuilt");
            return 0;
        }
    }

    @Command(name = "plugin", description = "Plugin jars in gateway/plugins.", subcommands = {PluginCommand.List.class, PluginCommand.Install.class, PluginCommand.Remove.class})
    static final class PluginCommand implements Callable<Integer> {
        @ParentCommand private GatewayCommand parent;
        GatewayCommand parent() { return parent; }

        @Override
        public Integer call() { return new List(this).call(); }

        @Command(name = "list", description = "Plugin types the gateway can serve.")
        static final class List implements Callable<Integer> {
            @ParentCommand private PluginCommand p;
            List() {}
            List(PluginCommand p) { this.p = p; }

            @Override
            public Integer call() {
                Context ctx = p.parent().ctx();
                java.util.List<GatewayPluginInfo> plugins = ctx.open().gatewayService().plugins();
                if (ctx.json()) { ctx.printJson(new PluginList(plugins)); return 0; }
                for (GatewayPluginInfo g : plugins) {
                    ctx.out().println(g.error() != null ? g.source() + "  ERROR: " + g.error()
                            : g.type() + "  " + g.label() + "  (" + g.source() + ", api " + g.apiVersion() + ", allowlist: " + g.allowlistLabel() + ")");
                }
                return 0;
            }
        }

        @Command(name = "install", description = "Validate a plugin jar and copy it into gateway/plugins.")
        static final class Install implements Callable<Integer> {
            @ParentCommand private PluginCommand p;
            @Parameters(index = "0", paramLabel = "<jar>") private Path jar;

            @Override
            public Integer call() {
                Context ctx = p.parent().ctx();
                GatewayPluginInfo info = ctx.open().gatewayService().pluginInstall(jar);
                if (ctx.json()) ctx.printJson(info); else ctx.out().println("installed " + info.source() + " (plugin type '" + info.type() + "'); restart the gateway to load it");
                return 0;
            }
        }

        @Command(name = "remove", description = "Delete a plugin jar from gateway/plugins.")
        static final class Remove implements Callable<Integer> {
            @ParentCommand private PluginCommand p;
            @Parameters(index = "0", paramLabel = "<jar-name>") private String name;

            @Override
            public Integer call() {
                Context ctx = p.parent().ctx();
                ctx.open().gatewayService().pluginRemove(name);
                if (ctx.json()) ctx.printJson(new Done("remove", name)); else ctx.out().println("removed " + name);
                return 0;
            }
        }
    }

    @Command(name = "instance", description = "Instances in gateway/gateway.manifest.json.", subcommands = {InstanceCommand.Add.class, InstanceCommand.Remove.class, InstanceCommand.Show.class})
    static final class InstanceCommand implements Callable<Integer> {
        @ParentCommand private GatewayCommand parent;
        GatewayCommand parent() { return parent; }

        @Override
        public Integer call() { return new ListCommand(parent).call(); }

        @Command(name = "add", description = "Add an instance and re-render (Squid hosts, certificate, compose).")
        static final class Add implements Callable<Integer> {
            @ParentCommand private InstanceCommand p;
            @Parameters(index = "0", paramLabel = "<id>", description = "^[a-z][a-z0-9-]*$; environment variables derive from it.") private String id;
            @Option(names = "--hostname", required = true, paramLabel = "<host>", description = "Hostname agents use, e.g. docs.gateway.local.") private String hostname;
            @Option(names = "--plugin", paramLabel = "<type>", description = "Plugin type (default: http).") private String plugin;
            @Option(names = "--label", paramLabel = "<text>") private String label;
            @Option(names = "--alias", paramLabel = "<host>", description = "Extra hostname (repeatable).") private java.util.List<String> aliases = new java.util.ArrayList<>();
            @Option(names = "--scheme", paramLabel = "<scheme>", description = "bearer (default), basic, header:<name> or query:<name>.") private String scheme;
            @Option(names = "--mode", paramLabel = "<mode>", description = "http plugin allowlist mode: path-prefix (default), path-exact, path-regex, query:<param>, none.") private String mode;
            @Option(names = "--config", paramLabel = "<json>", description = "Plugin configuration as a JSON object.") private String config;

            @Override
            public Integer call() throws IOException {
                Context ctx = p.parent().ctx();
                Map<String, Object> cfg = config == null || config.isBlank() ? Map.of() : JsonOutput.readObject(config, "--config");
                GatewayInstanceInfo info = ctx.open().gatewayService().instanceAdd(id, plugin, label, hostname, aliases, scheme, mode, cfg);
                if (ctx.json()) { ctx.printJson(info); return 0; }
                printInstance(ctx, info);
                ctx.out().println("next: lyngvi gateway secret set " + id + " --url <base-url> --token-stdin; lyngvi gateway allow " + id + " add <entry>; lyngvi gateway restart");
                return 0;
            }
        }

        @Command(name = "remove", description = "Remove an instance from the manifest (allowlist and secret files are kept).")
        static final class Remove implements Callable<Integer> {
            @ParentCommand private InstanceCommand p;
            @Parameters(index = "0", paramLabel = "<id>") private String id;

            @Override
            public Integer call() {
                Context ctx = p.parent().ctx();
                ctx.open().gatewayService().instanceRemove(id);
                if (ctx.json()) ctx.printJson(new Done("remove", id)); else ctx.out().println("removed instance " + id + "; restart the gateway to apply");
                return 0;
            }
        }

        @Command(name = "show", description = "One instance in detail.")
        static final class Show implements Callable<Integer> {
            @ParentCommand private InstanceCommand p;
            @Parameters(index = "0", paramLabel = "<id>") private String id;

            @Override
            public Integer call() {
                Context ctx = p.parent().ctx();
                GatewayInstanceInfo i = ctx.open().gatewayService().show(id);
                if (ctx.json()) { ctx.printJson(i); return 0; }
                printInstance(ctx, i);
                ctx.out().println("  env: " + i.baseUrlEnv() + ", " + i.secretEnv() + " (" + i.secretScheme() + ") in gateway/secrets/" + i.id() + ".env");
                ctx.out().println("  allowlist: gateway/allowlists/" + i.allowlistFile() + (i.allowlistMode() == null ? "" : " (mode " + i.allowlistMode() + ")"));
                ctx.out().println("  in the container: $" + i.urlEnvName());
                return 0;
            }
        }
    }

    @Command(name = "secret", description = "Base URL and secret of an instance (gateway/secrets/<id>.env).", subcommands = {SecretCommand.Set.class, SecretCommand.Status.class, SecretCommand.Clear.class})
    static final class SecretCommand implements Callable<Integer> {
        @ParentCommand private GatewayCommand parent;
        GatewayCommand parent() { return parent; }

        @Override
        public Integer call() {
            parent.ctx().err().println("Usage: lyngvi gateway secret set|status|clear <id>");
            return ErrorCode.INVALID_ARGUMENT.exitCode();
        }

        static void print(Context ctx, SecretStatus s) {
            if (ctx.json()) { ctx.printJson(s); return; }
            ctx.out().println(s.id() + ": " + (s.present() ? "secret set (" + s.masked() + ")" : "no secret")
                    + (s.baseUrl() == null ? "" : ", base URL " + s.baseUrl()) + " [" + s.file() + "]");
        }

        @Command(name = "set", description = "Write the base URL and/or the secret. The secret is read from stdin, never from an argument.")
        static final class Set implements Callable<Integer> {
            @ParentCommand private SecretCommand p;
            @Parameters(index = "0", paramLabel = "<id>") private String id;
            @Option(names = "--url", paramLabel = "<base-url>") private String url;
            @Option(names = "--token-stdin", description = "Read the secret (one line) from standard input.") private boolean tokenStdin;

            @Override
            public Integer call() throws IOException {
                Context ctx = p.parent().ctx();
                if (url == null && !tokenStdin) {
                    throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "Give --url and/or --token-stdin", Map.of("id", id));
                }
                String token = null;
                if (tokenStdin) {
                    token = new String(ctx.stdin().readAllBytes(), StandardCharsets.UTF_8).strip();
                    if (token.isEmpty()) throw new SandboxException(ErrorCode.INVALID_ARGUMENT, "No secret on standard input", Map.of("id", id));
                }
                print(ctx, ctx.open().gatewayService().secretSet(id, url, token));
                return 0;
            }
        }

        @Command(name = "status", description = "Whether the secret is set (masked).")
        static final class Status implements Callable<Integer> {
            @ParentCommand private SecretCommand p;
            @Parameters(index = "0", paramLabel = "<id>") private String id;

            @Override
            public Integer call() {
                Context ctx = p.parent().ctx();
                print(ctx, ctx.open().gatewayService().secretStatus(id));
                return 0;
            }
        }

        @Command(name = "clear", description = "Delete the secret file.")
        static final class Clear implements Callable<Integer> {
            @ParentCommand private SecretCommand p;
            @Parameters(index = "0", paramLabel = "<id>") private String id;

            @Override
            public Integer call() {
                Context ctx = p.parent().ctx();
                ctx.open().gatewayService().secretClear(id);
                if (ctx.json()) ctx.printJson(new Done("clear", id)); else ctx.out().println(id + ": secret cleared");
                return 0;
            }
        }
    }

    @Command(name = "allow", description = "An instance's allowlist; the running gateway picks up changes by itself.", subcommands = {AllowCommand.List.class, AllowCommand.Add.class, AllowCommand.Remove.class})
    static final class AllowCommand implements Callable<Integer> {
        @ParentCommand private GatewayCommand parent;
        @Parameters(index = "0", paramLabel = "<id>") String id;
        GatewayCommand parent() { return parent; }

        @Override
        public Integer call() { return new List(this).call(); }

        @Command(name = "list", description = "Entries in the allowlist file.")
        static final class List implements Callable<Integer> {
            @ParentCommand private AllowCommand p;
            List() {}
            List(AllowCommand p) { this.p = p; }

            @Override
            public Integer call() {
                Context ctx = p.parent().ctx();
                java.util.List<String> entries = ctx.open().gatewayService().allowList(p.id);
                if (ctx.json()) { ctx.printJson(new Entries(p.id, entries)); return 0; }
                if (entries.isEmpty()) ctx.out().println("(empty: the gateway denies everything for " + p.id + ")");
                for (String e : entries) ctx.out().println(e);
                return 0;
            }
        }

        @Command(name = "add", description = "Append an entry (path, id, key or browser URL, per plugin).")
        static final class Add implements Callable<Integer> {
            @ParentCommand private AllowCommand p;
            @Parameters(index = "0", paramLabel = "<entry>") private String entry;

            @Override
            public Integer call() {
                Context ctx = p.parent().ctx();
                boolean added = ctx.open().gatewayService().allowAdd(p.id, entry);
                if (ctx.json()) ctx.printJson(new Added(p.id, entry.strip(), added)); else ctx.out().println(added ? "added " + entry.strip() : entry.strip() + " was already allowed");
                return 0;
            }
        }

        @Command(name = "remove", description = "Remove an entry.")
        static final class Remove implements Callable<Integer> {
            @ParentCommand private AllowCommand p;
            @Parameters(index = "0", paramLabel = "<entry>") private String entry;

            @Override
            public Integer call() {
                Context ctx = p.parent().ctx();
                boolean removed = ctx.open().gatewayService().allowRemove(p.id, entry);
                if (ctx.json()) ctx.printJson(new Removed(p.id, entry.strip(), removed)); else ctx.out().println(removed ? "removed " + entry.strip() : entry.strip() + " was not on the list");
                return 0;
            }
        }
    }

    @Command(name = "cert", description = "The gateway's self-signed TLS certificate.", subcommands = {CertCommand.Renew.class, CertCommand.Show.class})
    static final class CertCommand implements Callable<Integer> {
        @ParentCommand private GatewayCommand parent;
        GatewayCommand parent() { return parent; }

        @Override
        public Integer call() { return new Show(this).call(); }

        @Command(name = "renew", description = "Issue a new certificate covering every gateway hostname.")
        static final class Renew implements Callable<Integer> {
            @ParentCommand private CertCommand p;

            @Override
            public Integer call() {
                Context ctx = p.parent().ctx();
                Sandbox sb = ctx.open();
                Path cert = sb.gatewayService().certRenew();
                String rel = sb.paths().root().relativize(cert).toString();
                if (ctx.json()) ctx.printJson(new Cert(rel, "restart the gateway to serve it")); else ctx.out().println("wrote " + rel + "; restart the gateway to serve it");
                return 0;
            }
        }

        @Command(name = "show", description = "Where the certificate is and which names it covers.")
        static final class Show implements Callable<Integer> {
            @ParentCommand private CertCommand p;
            Show() {}
            Show(CertCommand p) { this.p = p; }

            @Override
            public Integer call() {
                Context ctx = p.parent().ctx();
                Sandbox sb = ctx.open();
                GatewayService gw = sb.gatewayService();
                Path cert = gw.certGenerator().cert();
                String rel = sb.paths().root().relativize(cert).toString();
                java.util.List<String> hosts = gw.manifest().hostnames();
                boolean exists = java.nio.file.Files.isRegularFile(cert);
                if (ctx.json()) ctx.printJson(Map.of("certificate", rel, "exists", exists, "hostnames", hosts));
                else ctx.out().println(rel + (exists ? "" : " (missing; run rebuild-config)") + " for " + (hosts.isEmpty() ? "gateway" : String.join(", ", hosts)));
                return 0;
            }
        }
    }
}
