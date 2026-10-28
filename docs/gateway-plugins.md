# Writing a gateway plugin

The gateway holds the credentials for internal systems; the agent only sees a hostname and a read-only,
allowlisted API. Every system type is a plugin. `http` is built in; `confluence` and `jira` ship as jars.

## The contract (`lyngvi-gateway-api`, no dependencies)

```java
public final class MyPlugin implements GatewayPlugin {
    public String type() { return "mysystem"; }
    public String label() { return "My System"; }
    public String allowlistLabel() { return "records"; }
    public Class<?> configType() { return MyConfig.class; }          // instance.config, deserialised by the runtime
    public String restBase(Instance i) { return "/api/v2"; }
    public AllowlistResolver allowlist() { return AllowlistResolver.exact(); }
    public List<Route> routes() {
        return List.of(Route.get("/record/{id}", r -> r.param("id"), (req, ctx) -> {
            UpstreamResponse up = ctx.upstream().get("/records/" + req.param("id"), Map.of());
            return Response.json(200, ctx.json().write(ctx.json().readObject(new String(up.body()))));
        }));
    }
}
```

- The runtime enforces, around every route: unknown host 404, method gate (405 unless the instance has the
  `write` capability and the profile allows it), unconfigured 503, allowlist 403 (when the route names a key),
  then your handler; `cache-control: no-store`; secrets masked in logs; one instance failing never affects another.
- `ctx.upstream()` is origin-locked to the instance's base URL, injects the secret (`bearer`, `basic`,
  `header:<name>`, `query:<name>`) and never forwards the caller's `Authorization`.
- `AllowlistResolver` turns allowlist entries into keys: `exact`, `exactIgnoreCase`, `pathPrefix`, `regex`, or your
  own (`parse` for direct entries, `resolve` for entries that need an upstream lookup, cached five minutes).
- `parseBrowserUrl` lets an agent paste a browser URL; `health()` backs `/healthz?deep=1`.

## Packaging

Put `META-INF/services/no.syntakserror.lyngvi.gateway.api.GatewayPlugin` with your class name in the jar.
Install with `setup.sh gateway plugin install my-plugin.jar` (validated on the host), add an instance with
`setup.sh gateway instance add mine --plugin mysystem --hostname mine.gateway.local`, set the secret and
allowlist, then `setup.sh gateway restart`.

## Testing

`lyngvi-gateway`'s test jar provides `GatewayHarness`: a gateway on an ephemeral port with an in-memory
manifest, allowlist files and `StubUpstream` responses. See `lyngvi-gateway-plugins` for the reference tests.
