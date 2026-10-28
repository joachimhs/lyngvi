package no.syntakserror.lyngvi.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextTest {
    @Test
    void htmlBecomesReadableText() {
        assertThat(new Text().htmlToText("<h1>Title</h1><p>One &amp; two<br/>three</p>")).isEqualTo("Title\nOne & two\nthree");
    }

    @Test
    void inlineWikiMarkersAreDropped() {
        assertThat(new Text().wikiMarkupToText("*bold* and _it_ and {{mono}} but 2*3*4")).isEqualTo("bold and it and mono but 2*3*4");
    }

    @Test
    void wikiMarkupBecomesReadableText() {
        assertThat(new Text().wikiMarkupToText("h2. Head\n* item [link|http://x]\n{code}x{code}")).isEqualTo("Head\n- item link\n\nx");
    }
}
