package no.syntakserror.lyngvi.gateway;

import java.io.IOException;
import java.io.InputStream;

/** Entry point of the gateway container. Reads its configuration from the environment; see spec §8.10. */
public final class GatewayMain {

    private GatewayMain() {}

    public static void main(String[] args) throws IOException {
        if (args.length > 0 && args[0].equals("--health")) {
            System.exit(health(System.getenv(), args.length > 1 && args[1].equals("--deep")));
        }
        Gateway gateway = Gateway.fromEnvironment(System.getenv(), version());
        gateway.start();
        Runtime.getRuntime().addShutdownHook(new Thread(gateway::stop));
        Thread.currentThread().setName("gateway-main");
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** {@code --health [--deep]}: fetches {@code /healthz} from the local listener and prints it. Exit 0 when ok. */
    static int health(java.util.Map<String, String> env, boolean deep) {
        String port = env.getOrDefault("PORT", "8080");
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build();
        try {
            var r = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/healthz" + (deep ? "?deep=1" : "")))
                    .timeout(java.time.Duration.ofSeconds(deep ? 20 : 5)).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            System.out.println(r.body());
            return r.statusCode() == 200 && r.body().contains("\"ok\":true") ? 0 : 1;
        } catch (IOException | InterruptedException e) {
            System.out.println("{\"ok\":false,\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}");
            return 1;
        }
    }

    public static String version() {
        try (InputStream in = GatewayMain.class.getResourceAsStream("/VERSION")) {
            return in == null ? "dev" : new String(in.readAllBytes()).strip();
        } catch (IOException e) {
            return "dev";
        }
    }
}
