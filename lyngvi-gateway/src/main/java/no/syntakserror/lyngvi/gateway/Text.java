package no.syntakserror.lyngvi.gateway;

import no.syntakserror.lyngvi.gateway.api.TextUtil;

/** Text conversions ported from the original gateway's {@code core/text.js} (spec §8.6). */
public final class Text implements TextUtil {

    @Override
    public String htmlToText(String html) {
        if (html == null || html.isEmpty()) return "";
        String s = html.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|li|h[1-6]|tr)>", "\n")
                .replaceAll("<[^>]+>", "");
        s = decodeEntities(s);
        return collapse(s);
    }

    @Override
    public String wikiMarkupToText(String markup) {
        if (markup == null || markup.isEmpty()) return "";
        String s = markup.replace("\r\n", "\n")
                .replaceAll("\\{code(:[^}]*)?}", "\n")
                .replaceAll("\\{noformat}", "\n")
                .replaceAll("\\{quote}", "\n")
                .replaceAll("\\{color(:[^}]*)?}", "")
                .replaceAll("\\{panel(:[^}]*)?}", "\n")
                .replaceAll("\\[([^|\\]]+)\\|[^]]+]", "$1")
                .replaceAll("(?m)^\\s*[*#-]+\\s+", "- ")
                .replaceAll("(?<![\\w*])\\*([^*\\n]+)\\*(?![\\w*])", "$1")
                .replaceAll("(?<![\\w_])_([^_\\n]+)_(?![\\w_])", "$1")
                .replaceAll("\\{\\{([^}]*)}}", "$1")
                .replaceAll("(?m)^h[1-6]\\.\\s*", "");
        return collapse(s);
    }

    @Override
    public String stripTags(String html) {
        return html == null ? "" : decodeEntities(html.replaceAll("<[^>]+>", ""));
    }

    static String decodeEntities(String s) {
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
    }

    static String collapse(String s) {
        return s.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").strip();
    }
}
