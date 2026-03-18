package app.botdrop;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OpenclawModelListUtilsTest {

    @Test
    public void buildPreferredModelListCommand_prefersModelRegistryAndFallsBackToOpenclawCli() {
        String command = OpenclawModelListUtils.buildPreferredModelListCommand();

        assertTrue(command.contains("model_registry_js=\"\""));
        assertTrue(command.contains("for candidate in \\"));
        assertTrue(command.contains(
            "$PREFIX/lib/node_modules/openclaw/node_modules/@mariozechner/pi-coding-agent/dist/core/model-registry.js"));
        assertTrue(command.contains(
            "$PREFIX/share/botdrop/openclaw-runtime/current/node_modules/@mariozechner/pi-coding-agent/dist/core/model-registry.js"));
        assertTrue(command.contains("if [ -f \"$candidate\" ]; then"));
        assertTrue(command.contains("model_registry_js=\"$candidate\""));
        assertTrue(command.contains("setFallbackResolver() {}"));
        assertTrue(command.contains("registry.getAll()"));
        assertTrue(command.contains("model.provider + '/' + model.id"));
        assertTrue(command.contains("termux-chroot"));
        assertTrue(command.contains("LD_LIBRARY_PATH"));
        assertTrue(command.contains("openclaw models list --all --plain"));
    }

    @Test
    public void fallbackModelListCommand_matchesOpenclawCli() {
        assertEquals(
            OpenclawVersionUtils.MODEL_LIST_COMMAND,
            OpenclawModelListUtils.FALLBACK_MODEL_LIST_COMMAND
        );
    }

    @Test
    public void buildPreferredModelListCommand_withTrace_enablesFallbackTracing() {
        String command = OpenclawModelListUtils.buildPreferredModelListCommand(true);

        assertTrue(command.contains("BOTDROP_TRACE_NPM_REGISTRY=1 " + OpenclawVersionUtils.MODEL_LIST_COMMAND));
    }

    @Test
    public void buildPreferredModelListCommand_usesBuiltInRegistryWhenModelsJsonIsMissing() {
        String command = OpenclawModelListUtils.buildPreferredModelListCommand(true);

        assertTrue(command.contains("if [ -f \"$models_json\" ]; then"));
        assertTrue(command.contains("  export BOTDROP_MODELS_JSON=\"$models_json\""));
        assertTrue(command.contains("  unset BOTDROP_MODELS_JSON"));
        assertTrue(command.contains(
            "const registry = process.env.BOTDROP_MODELS_JSON\n"
                + "  ? new ModelRegistry(fakeAuth, process.env.BOTDROP_MODELS_JSON)\n"
                + "  : new ModelRegistry(fakeAuth);"));
    }
}
