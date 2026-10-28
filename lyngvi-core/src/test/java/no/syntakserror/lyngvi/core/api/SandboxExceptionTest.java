package no.syntakserror.lyngvi.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SandboxExceptionTest {

    @Test
    void errorCodesMapToTheDocumentedExitCodes() {
        assertThat(ErrorCode.INTERNAL.exitCode()).isEqualTo(1);
        assertThat(ErrorCode.INVALID_ARGUMENT.exitCode()).isEqualTo(2);
        assertThat(ErrorCode.NOT_INITIALIZED.exitCode()).isEqualTo(3);
        assertThat(ErrorCode.VALIDATION_FAILED.exitCode()).isEqualTo(4);
        assertThat(ErrorCode.EXTERNAL_COMMAND_FAILED.exitCode()).isEqualTo(5);
        assertThat(ErrorCode.INTERRUPTED.exitCode()).isEqualTo(6);
        assertThat(ErrorCode.NO_CHANGES.exitCode()).isEqualTo(7);
    }

    @Test
    void carriesCodeMessageAndData() {
        SandboxException e = new SandboxException(
                ErrorCode.NOT_INITIALIZED, "Sandbox is not initialised", Map.of("dir", "/tmp/x"));

        assertThat(e.code()).isEqualTo(ErrorCode.NOT_INITIALIZED);
        assertThat(e.getMessage()).isEqualTo("Sandbox is not initialised");
        assertThat(e.data()).containsExactly(Map.entry("dir", "/tmp/x"));
        assertThat(e.exitCode()).isEqualTo(3);
    }

    @Test
    void dataDefaultsToEmptyAndIsImmutable() {
        SandboxException e = new SandboxException(ErrorCode.INTERNAL, "boom");

        assertThat(e.data()).isEmpty();
        assertThatThrownBy(() -> e.data().put("k", "v")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void keepsTheCause() {
        RuntimeException cause = new RuntimeException("root");
        SandboxException e = new SandboxException(ErrorCode.INTERNAL, "boom", cause);

        assertThat(e.getCause()).isSameAs(cause);
    }
}
