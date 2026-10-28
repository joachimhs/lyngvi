package no.syntakserror.lyngvi.gui.view;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The FXML contract (1.1.2 Del E, kept): every {@code fx:id} has a field in its controller (an include {@code x} may bind
 * to {@code xController}), every {@code #handler} has a method, every element tag is imported and exists, every
 * {@code %key} exists in the bundle, and every rail item keeps its icon and its label. Parsed from source: no toolkit needed.
 */
class FxmlBindingTest {

    private static final Path FXML_DIR = Path.of("src/main/resources/no/syntakserror/lyngvi/gui/fxml");
    private static final Path SRC = Path.of("src/main/java");
    private static final Pattern CONTROLLER = Pattern.compile("fx:controller=\"([\\w.]+)\"");
    private static final Pattern FX_ID = Pattern.compile("fx:id=\"(\\w+)\"");
    private static final Pattern HANDLER = Pattern.compile("on\\w+=\"#(\\w+)\"");
    private static final Pattern IMPORT = Pattern.compile("<\\?import ([\\w.]+)\\?>");
    private static final Pattern TAG = Pattern.compile("<([A-Z]\\w*)[\\s/>]");
    private static final Pattern KEY = Pattern.compile("%([\\w.]+)");
    private static final Pattern INCLUDE = Pattern.compile("<fx:include fx:id=\"(\\w+)\"");
    private static final Pattern FIELD = Pattern.compile("@FXML\\s+(?:private|public|protected)?\\s*[\\w.<>,\\s]+?\\s(\\w+);");
    private static final Pattern METHOD = Pattern.compile("@FXML\\s+(?:private|public|protected)?\\s*\\w+\\s+(\\w+)\\s*\\(");

    private static List<Path> fxmlFiles() throws Exception {
        try (Stream<Path> s = Files.list(FXML_DIR)) {
            return s.filter(p -> p.toString().endsWith(".fxml")).sorted().toList();
        }
    }

    private static String controllerSource(String className) throws Exception {
        Path f = SRC.resolve(className.replace('.', '/') + ".java");
        assertThat(f).as("controller source for " + className).isRegularFile();
        return Files.readString(f);
    }

    @Test
    void everyFxIdAndHandlerHasACounterpartInTheController() throws Exception {
        int checked = 0;
        for (Path fxml : fxmlFiles()) {
            String xml = Files.readString(fxml);
            Matcher c = CONTROLLER.matcher(xml);
            assertThat(c.find()).as(fxml + " declares fx:controller").isTrue();
            String src = controllerSource(c.group(1));
            Set<String> fields = new HashSet<>();
            Matcher fm = FIELD.matcher(src);
            while (fm.find()) fields.add(fm.group(1));
            Set<String> methods = new HashSet<>();
            Matcher mm = METHOD.matcher(src);
            while (mm.find()) methods.add(mm.group(1));
            // public handlers called from other controllers may lack @FXML but must still exist
            Matcher pm = Pattern.compile("public void (\\w+)\\(\\)").matcher(src);
            while (pm.find()) methods.add(pm.group(1));
            Set<String> includes = new HashSet<>();
            Matcher im = INCLUDE.matcher(xml);
            while (im.find()) includes.add(im.group(1));
            Matcher idm = FX_ID.matcher(xml);
            List<String> missing = new ArrayList<>();
            while (idm.find()) {
                String id = idm.group(1);
                checked++;
                if (xml.contains("<fx:define>") && Pattern.compile("<ToggleGroup fx:id=\"" + id + "\"").matcher(xml).find()) continue;
                boolean ok = fields.contains(id) || (includes.contains(id) && fields.contains(id + "Controller"));
                if (!ok) missing.add(id);
            }
            assertThat(missing).as("fx:ids without a field in " + c.group(1)).isEmpty();
            Matcher hm = HANDLER.matcher(xml);
            List<String> missingHandlers = new ArrayList<>();
            while (hm.find()) {
                checked++;
                if (!methods.contains(hm.group(1))) missingHandlers.add(hm.group(1));
            }
            assertThat(missingHandlers).as("handlers without a method in " + c.group(1)).isEmpty();
        }
        assertThat(checked).isGreaterThan(100);
    }

    @Test
    void everyTagIsImportedAndEveryImportExists() throws Exception {
        for (Path fxml : fxmlFiles()) {
            String xml = Files.readString(fxml);
            Set<String> imported = new HashSet<>();
            Matcher im = IMPORT.matcher(xml);
            while (im.find()) {
                String cls = im.group(1);
                assertThat(classExists(cls)).as(cls + " imported by " + fxml.getFileName()).isTrue();
                imported.add(cls.substring(cls.lastIndexOf('.') + 1));
            }
            Matcher tm = TAG.matcher(xml.replaceAll("<\\?.*?\\?>", ""));
            Set<String> unimported = new HashSet<>();
            while (tm.find()) if (!imported.contains(tm.group(1))) unimported.add(tm.group(1));
            assertThat(unimported).as("tags without <?import?> in " + fxml.getFileName()).isEmpty();
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, FxmlBindingTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Test
    void everyBundleKeyUsedByFxmlAndControllersExistsInBothLanguages() throws Exception {
        ResourceBundle en = ResourceBundle.getBundle("no.syntakserror.lyngvi.core.i18n.messages", Locale.ENGLISH);
        ResourceBundle nb = ResourceBundle.getBundle("no.syntakserror.lyngvi.core.i18n.messages", Locale.forLanguageTag("nb"));
        Set<String> keys = new HashSet<>();
        for (Path fxml : fxmlFiles()) {
            Matcher km = KEY.matcher(Files.readString(fxml));
            while (km.find()) keys.add(km.group(1));
        }
        try (Stream<Path> s = Files.walk(SRC)) {
            for (Path java : s.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher km = Pattern.compile("\"(gui\\.[a-z0-9_.]+[a-z0-9_])\"").matcher(Files.readString(java));
                while (km.find()) keys.add(km.group(1));
            }
        }
        // Keys built at run time from data: stop reasons, tool categories and delivery classification reasons.
        keys.addAll(List.of("gui.chat.stop.error", "gui.chat.stop.cancelled"));
        for (ToolCall.Category c : ToolCall.Category.values()) keys.add("gui.chat.tool." + c.key());
        for (String reason : List.of("SYMLINK", "BINARY", "EXECUTABLE", "TOO_LARGE", "PATH_TRAVERSAL", "ABSOLUTE_PATH", "GIT_PATH")) {
            keys.add("gui.deliver.reason." + reason.toLowerCase(Locale.ROOT));
        }
        assertThat(keys.size()).isGreaterThan(200);
        List<String> missing = new ArrayList<>();
        for (String k : keys) {
            if (!en.containsKey(k)) missing.add(k + " (en)");
            if (!nb.containsKey(k)) missing.add(k + " (nb)");
        }
        assertThat(missing).isEmpty();
    }

    /**
     * The nav rail is an icon over a one-word label (spec §9.3). A rail item that loses its {@code text} or its
     * {@code contentDisplay} still looks reasonable — icons only, at the rail's minimum width — so nothing else
     * catches it; this does.
     */
    @Test
    void everyRailItemHasAnIconAndAShortLabelAboveIt() throws Exception {
        String xml = Files.readString(FXML_DIR.resolve("main.fxml"));
        List<String> items = new ArrayList<>();
        Matcher bm = Pattern.compile("<ToggleButton\\b[^>]*?styleClass=\"rail-button\".*?</ToggleButton>", Pattern.DOTALL).matcher(xml);
        while (bm.find()) items.add(bm.group());
        assertThat(items).as("rail items in main.fxml").hasSize(8);
        List<String> wrong = new ArrayList<>();
        for (String item : items) {
            String id = FX_ID.matcher(item).results().findFirst().map(r -> r.group(1)).orElse(item);
            if (!item.contains("contentDisplay=\"TOP\"")) wrong.add(id + " (no contentDisplay=TOP)");
            if (!Pattern.compile("text=\"%gui\\.nav\\.short\\.\\w+\"").matcher(item).find()) wrong.add(id + " (no short label)");
            if (!item.contains("<Icon ")) wrong.add(id + " (no icon)");
            if (!item.contains("accessibleText=\"%gui.nav.")) wrong.add(id + " (no accessible name)");
        }
        assertThat(wrong).as("rail items missing icon, label or accessible name").isEmpty();
    }
}
