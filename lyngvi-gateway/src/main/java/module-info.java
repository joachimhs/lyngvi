/** The gateway runtime. */
module no.syntakserror.lyngvi.gateway {
    requires no.syntakserror.lyngvi.gateway.api;
    requires com.fasterxml.jackson.databind;
    requires jdk.httpserver;
    requires java.net.http;

    exports no.syntakserror.lyngvi.gateway;

    opens no.syntakserror.lyngvi.gateway to com.fasterxml.jackson.databind;
}
