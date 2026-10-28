package no.syntakserror.lyngvi.gui.view;

import java.util.List;
import java.util.Map;
import javafx.scene.Group;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;

/**
 * A 16px line icon drawn from SVG paths (24-unit grid, scaled), so the app needs no icon font or image files.
 * Colour comes from CSS: {@code .icon-stroke} / {@code .icon-fill} follow the surrounding button state.
 * Usable from FXML as {@code <Icon fx:value="refresh"/>}.
 */
public final class Icon extends StackPane {

    private record Spec(boolean filled, List<String> paths) {
        static Spec stroke(String... paths) { return new Spec(false, List.of(paths)); }
        static Spec fill(String... paths) { return new Spec(true, List.of(paths)); }
    }

    private static final double GRID = 24;
    private static final double SIZE = 16;

    private static final Map<String, Spec> ICONS = Map.ofEntries(
            Map.entry("refresh", Spec.stroke("M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5",
                    "M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16", "M16 16h5v5")),
            Map.entry("sun", Spec.stroke("M12 8a4 4 0 1 0 0 8 4 4 0 1 0 0-8Z", "M12 2v2", "M12 20v2", "m4.93 4.93 1.41 1.41",
                    "m17.66 17.66 1.41 1.41", "M2 12h2", "M20 12h2", "m6.34 17.66-1.41 1.41", "m19.07 4.93-1.41 1.41")),
            Map.entry("moon", Spec.stroke("M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z")),
            Map.entry("plus", Spec.stroke("M5 12h14", "M12 5v14")),
            Map.entry("x", Spec.stroke("M18 6 6 18", "m6 6 12 12")),
            Map.entry("check", Spec.stroke("M20 6 9 17l-5-5")),
            Map.entry("send", Spec.stroke("m22 2-7 20-4-9-9-4Z", "M22 2 11 13")),
            Map.entry("stop", Spec.fill("M7 5h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2Z")),
            Map.entry("play", Spec.fill("M7 4.5v15a1 1 0 0 0 1.5.87l13-7.5a1 1 0 0 0 0-1.74l-13-7.5A1 1 0 0 0 7 4.5Z")),
            Map.entry("chevron-down", Spec.stroke("m6 9 6 6 6-6")),
            Map.entry("arrow-down", Spec.stroke("M12 5v14", "m19 12-7 7-7-7")),
            Map.entry("arrow-up-circle", Spec.stroke("M12 2a10 10 0 1 0 0 20 10 10 0 1 0 0-20Z", "m16 12-4-4-4 4", "M12 16V8")),
            Map.entry("paperclip", Spec.stroke("m21.44 11.05-9.19 9.19a6 6 0 0 1-8.49-8.49l8.57-8.57A4 4 0 1 1 18 8.84l-8.59 8.57a2 2 0 0 1-2.83-2.83l8.49-8.48")),
            Map.entry("more", Spec.fill("M5 10.5a1.5 1.5 0 1 0 0 3 1.5 1.5 0 1 0 0-3Z", "M12 10.5a1.5 1.5 0 1 0 0 3 1.5 1.5 0 1 0 0-3Z",
                    "M19 10.5a1.5 1.5 0 1 0 0 3 1.5 1.5 0 1 0 0-3Z")),
            Map.entry("help", Spec.stroke("M12 2a10 10 0 1 0 0 20 10 10 0 1 0 0-20Z", "M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3", "M12 17h.01")),
            Map.entry("info", Spec.stroke("M12 2a10 10 0 1 0 0 20 10 10 0 1 0 0-20Z", "M12 16v-4", "M12 8h.01")),
            Map.entry("folder", Spec.stroke("M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z")),
            Map.entry("box", Spec.stroke("M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z",
                    "m3.3 7 8.7 5 8.7-5", "M12 22V12")),
            Map.entry("shield", Spec.stroke("M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z")),
            Map.entry("shield-check", Spec.stroke("M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
                    "m9 12 2 2 4-4")),
            Map.entry("bot", Spec.stroke("M12 8V4H8", "M4 8h16a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2Z", "M2 14h2", "M20 14h2",
                    "M15 13v2", "M9 13v2")),
            Map.entry("server", Spec.stroke("M4 2h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2Z",
                    "M4 14h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2Z", "M6 6h.01", "M6 18h.01")),
            Map.entry("chat", Spec.stroke("M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2Z")),
            Map.entry("book", Spec.stroke("M12 7v14",
                    "M3 18a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h5a4 4 0 0 1 4 4 4 4 0 0 1 4-4h5a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1h-6a3 3 0 0 0-3 3 3 3 0 0 0-3-3Z")),
            Map.entry("sparkles", Spec.stroke("M9.94 14.34 9 17l-.94-2.66a3 3 0 0 0-1.4-1.4L4 12l2.66-.94a3 3 0 0 0 1.4-1.4L9 7l.94 2.66a3 3 0 0 0 1.4 1.4L14 12l-2.66.94a3 3 0 0 0-1.4 1.4Z",
                    "M19 3v4", "M17 5h4", "M18 15v2", "M17 16h2")),
            Map.entry("branch", Spec.stroke("M6 3v12", "M18 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z", "M6 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z", "M18 9a9 9 0 0 1-9 9")),
            Map.entry("globe", Spec.stroke("M12 2a10 10 0 1 0 0 20 10 10 0 1 0 0-20Z", "M2 12h20",
                    "M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10Z")),
            Map.entry("sliders", Spec.stroke("M4 21v-7", "M4 10V3", "M12 21v-9", "M12 8V3", "M20 21v-5", "M20 12V3", "M1 14h6", "M9 8h6", "M17 16h6")),
            Map.entry("wrench", Spec.stroke("M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z")),
            Map.entry("hammer", Spec.stroke("M14 3 21 10 17 14 10 7Z", "m12.5 9.5-9.5 9.5a1.5 1.5 0 0 0 2 2l9.5-9.5")),
            Map.entry("settings", Spec.stroke("M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z",
                    "M12 9a3 3 0 1 0 0 6 3 3 0 1 0 0-6Z")),
            Map.entry("diff", Spec.stroke("M12 3v14", "M5 10h14", "M5 21h14")),
            Map.entry("upload", Spec.stroke("M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m17 8-5-5-5 5", "M12 3v12")),
            Map.entry("save", Spec.stroke("M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2Z", "M17 21v-8H7v8", "M7 3v5h8")),
            Map.entry("undo", Spec.stroke("M3 7v6h6", "M21 17a9 9 0 0 0-9-9 9 9 0 0 0-6 2.3L3 13")),
            Map.entry("trash", Spec.stroke("M3 6h18", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6", "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2", "M10 11v6", "M14 11v6")),
            Map.entry("login", Spec.stroke("M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4", "m10 17 5-5-5-5", "M15 12H3")),
            Map.entry("logout", Spec.stroke("M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4", "m16 17 5-5-5-5", "M21 12H9")),
            Map.entry("search", Spec.stroke("M11 3a8 8 0 1 0 0 16 8 8 0 1 0 0-16Z", "m21 21-4.35-4.35")),
            Map.entry("terminal", Spec.stroke("m4 17 6-6-6-6", "M12 19h8")),
            Map.entry("activity", Spec.stroke("M22 12h-4l-3 9L9 3l-3 9H2")),
            Map.entry("file-text", Spec.stroke("M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z", "M14 2v4a2 2 0 0 0 2 2h4", "M10 9H8", "M16 13H8", "M16 17H8")),
            Map.entry("eye", Spec.stroke("M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z", "M12 9a3 3 0 1 0 0 6 3 3 0 1 0 0-6Z")),
            Map.entry("chart", Spec.stroke("M3 3v18h18", "M18 17V9", "M13 17V5", "M8 17v-3")),
            Map.entry("key", Spec.stroke("m21 2-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0 3 3L22 7l-3-3m-3.5 3.5L19 4")),
            Map.entry("star", Spec.stroke("M11.53 2.3a.53.53 0 0 1 .95 0l2.3 4.68a2.1 2.1 0 0 0 1.6 1.16l5.16.76a.53.53 0 0 1 .3.9l-3.74 3.64a2.1 2.1 0 0 0-.61 1.88l.88 5.14a.53.53 0 0 1-.77.56l-4.62-2.43a2.1 2.1 0 0 0-1.97 0L6.4 21.01a.53.53 0 0 1-.77-.56l.88-5.14a2.1 2.1 0 0 0-.61-1.88L2.16 9.8a.53.53 0 0 1 .3-.9l5.16-.76a2.1 2.1 0 0 0 1.6-1.16z")),
            Map.entry("star-filled", Spec.fill("M11.53 2.3a.53.53 0 0 1 .95 0l2.3 4.68a2.1 2.1 0 0 0 1.6 1.16l5.16.76a.53.53 0 0 1 .3.9l-3.74 3.64a2.1 2.1 0 0 0-.61 1.88l.88 5.14a.53.53 0 0 1-.77.56l-4.62-2.43a2.1 2.1 0 0 0-1.97 0L6.4 21.01a.53.53 0 0 1-.77-.56l.88-5.14a2.1 2.1 0 0 0-.61-1.88L2.16 9.8a.53.53 0 0 1 .3-.9l5.16-.76a2.1 2.1 0 0 0 1.6-1.16z")),
            Map.entry("package", Spec.stroke("M16.5 9.4 7.55 4.24", "M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z",
                    "M3.27 6.96 12 12.01l8.73-5.05", "M12 22.08V12")));

    private Icon(Spec spec) {
        getStyleClass().add("icon");
        Rectangle anchor = new Rectangle(GRID, GRID, Color.TRANSPARENT);
        anchor.setStroke(null);
        Group g = new Group(anchor);
        for (String d : spec.paths()) {
            SVGPath p = new SVGPath();
            p.setContent(d);
            p.getStyleClass().add(spec.filled() ? "icon-fill" : "icon-stroke");
            g.getChildren().add(p);
        }
        g.setScaleX(SIZE / GRID);
        g.setScaleY(SIZE / GRID);
        setMinSize(SIZE, SIZE);
        setPrefSize(SIZE, SIZE);
        setMaxSize(SIZE, SIZE);
        setMouseTransparent(true);
        getChildren().add(g);
    }

    /** The icon with this name; unknown names fail loudly so a typo in FXML is caught at load time. */
    public static Icon of(String name) {
        Spec spec = ICONS.get(name);
        if (spec == null) throw new IllegalArgumentException("unknown icon: " + name);
        return new Icon(spec);
    }

    /** FXML entry point: {@code <Icon fx:value="name"/>}. */
    public static Icon valueOf(String name) {
        return of(name);
    }

    /** Every icon name, for tests. */
    public static java.util.Set<String> names() {
        return ICONS.keySet();
    }
}
