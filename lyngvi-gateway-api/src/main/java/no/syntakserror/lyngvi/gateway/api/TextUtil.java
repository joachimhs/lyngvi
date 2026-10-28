package no.syntakserror.lyngvi.gateway.api;

/** Text conversions the runtime provides (ported from the original gateway). */
public interface TextUtil {

    String htmlToText(String html);

    String wikiMarkupToText(String markup);

    String stripTags(String html);
}
