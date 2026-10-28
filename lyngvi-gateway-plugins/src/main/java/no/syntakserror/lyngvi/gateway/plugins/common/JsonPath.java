package no.syntakserror.lyngvi.gateway.plugins.common;

import java.util.List;
import java.util.Map;

/** Tiny helpers for reading nested values out of parsed JSON maps. */
public final class JsonPath {

    private JsonPath() {}

    /** {@code get(map, "a", "b")} = map.a.b or null. */
    public static Object get(Object root, String... path) {
        Object cur = root;
        for (String p : path) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(p);
        }
        return cur;
    }

    public static String str(Object root, String... path) {
        Object v = get(root, path);
        return v == null ? null : String.valueOf(v);
    }

    public static Integer integer(Object root, String... path) {
        Object v = get(root, path);
        return v instanceof Number n ? n.intValue() : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object root, String... path) {
        Object v = get(root, path);
        return v instanceof List<?> l ? (List<Object>) l : List.of();
    }
}
