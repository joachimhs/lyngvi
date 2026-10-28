package no.syntakserror.lyngvi.gateway.plugins.jira;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import no.syntakserror.lyngvi.gateway.api.AllowlistResolver;
import no.syntakserror.lyngvi.gateway.api.Upstream;
import no.syntakserror.lyngvi.gateway.plugins.common.Urls;

/**
 * Entries: an issue key ({@code OPS-7}), a project key ({@code OPS}), or a browser URL ({@code …/browse/OPS-7}).
 * Keys are {@code issue:OPS-7} and {@code project:OPS}; an issue matches when its project is allowed.
 */
final class JiraResolver implements AllowlistResolver {

    static final Pattern ISSUE = Pattern.compile("^([A-Za-z][A-Za-z0-9_]*)-(\\d+)$");
    private static final Pattern BROWSE = Pattern.compile("/browse/([A-Za-z][A-Za-z0-9_]*-\\d+)");
    private static final Pattern PROJECT_URL = Pattern.compile("/projects/([A-Za-z][A-Za-z0-9_]*)");

    static String issueKey(String key) {
        return "issue:" + key.toUpperCase(Locale.ROOT);
    }

    static String projectKey(String key) {
        return "project:" + key.toUpperCase(Locale.ROOT);
    }

    static String projectOf(String issue) {
        Matcher m = ISSUE.matcher(issue);
        return m.matches() ? m.group(1) : null;
    }

    @Override
    public String parse(String entry) {
        String e = entry.strip();
        if (ISSUE.matcher(e).matches()) return issueKey(e);
        if (e.matches("[A-Za-z][A-Za-z0-9_]*")) return projectKey(e);
        Urls.Parsed p = Urls.parse(e);
        if (p == null) return null;
        Matcher b = BROWSE.matcher(p.path());
        if (b.find()) return issueKey(b.group(1));
        Matcher pr = PROJECT_URL.matcher(p.path());
        if (pr.find()) return projectKey(pr.group(1));
        return null;
    }

    @Override
    public List<String> resolve(String entry, Upstream upstream) {
        return List.of();
    }

    @Override
    public boolean matches(String key, Set<String> keys) {
        if (keys.contains(key)) return true;
        if (key.startsWith("issue:")) {
            String project = projectOf(key.substring(6));
            return project != null && keys.contains(projectKey(project));
        }
        return false;
    }
}
