/** JavaFX front-end of Lyngvi: views and viewmodels over the core; no logic of its own. */
module no.syntakserror.lyngvi.gui {
    requires no.syntakserror.lyngvi.core;
    requires no.syntakserror.lyngvi.gateway.api;
    requires javafx.controls;
    requires javafx.fxml;
    requires java.desktop;

    exports no.syntakserror.lyngvi.gui;
    exports no.syntakserror.lyngvi.gui.viewmodel;
    exports no.syntakserror.lyngvi.gui.view;

    opens no.syntakserror.lyngvi.gui to javafx.fxml;
    opens no.syntakserror.lyngvi.gui.view to javafx.fxml;
}
