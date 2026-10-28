/** Core module of Lyngvi: all logic, no user interface. */
module no.syntakserror.lyngvi.core {
    requires transitive no.syntakserror.lyngvi.gateway.api;
    requires com.fasterxml.jackson.databind;
    requires jdk.management;
    requires java.net.http;

    exports no.syntakserror.lyngvi.core.api;
    exports no.syntakserror.lyngvi.core.agents;
    exports no.syntakserror.lyngvi.core.ask;
    exports no.syntakserror.lyngvi.core.config;
    exports no.syntakserror.lyngvi.core.contract;
    exports no.syntakserror.lyngvi.core.delivery;
    exports no.syntakserror.lyngvi.core.doctor;
    exports no.syntakserror.lyngvi.core.gateway;
    exports no.syntakserror.lyngvi.core.i18n;
    exports no.syntakserror.lyngvi.core.migrate;
    exports no.syntakserror.lyngvi.core.render;
    exports no.syntakserror.lyngvi.core.runtime;
    exports no.syntakserror.lyngvi.core.session;
    exports no.syntakserror.lyngvi.core.skills;
    exports no.syntakserror.lyngvi.core.stack;
    exports no.syntakserror.lyngvi.core.template;
    exports no.syntakserror.lyngvi.core.version;

    opens no.syntakserror.lyngvi.core.ask to com.fasterxml.jackson.databind;
    opens no.syntakserror.lyngvi.core.config to com.fasterxml.jackson.databind;
    opens no.syntakserror.lyngvi.core.contract to com.fasterxml.jackson.databind;
    opens no.syntakserror.lyngvi.core.doctor to com.fasterxml.jackson.databind;
    opens no.syntakserror.lyngvi.core.stack to com.fasterxml.jackson.databind;
    opens no.syntakserror.lyngvi.core.render to com.fasterxml.jackson.databind;
    opens no.syntakserror.lyngvi.core.session to com.fasterxml.jackson.databind;
    opens no.syntakserror.lyngvi.core.skills to com.fasterxml.jackson.databind;
}
