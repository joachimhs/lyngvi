/** Command-line front-end for Lyngvi. */
module no.syntakserror.lyngvi.cli {
    requires no.syntakserror.lyngvi.core;
    requires info.picocli;

    opens no.syntakserror.lyngvi.cli to info.picocli;
}
