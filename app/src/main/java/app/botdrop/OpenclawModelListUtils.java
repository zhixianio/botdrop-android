package app.botdrop;

/**
 * Builds the preferred model-list command.
 *
 * Fast path: load models through pi-coding-agent's ModelRegistry without starting the full
 * OpenClaw CLI bundle. If that path fails for any reason, fall back to the standard OpenClaw
 * command so behavior remains compatible.
 */
public final class OpenclawModelListUtils {

    public static final String FALLBACK_MODEL_LIST_COMMAND = OpenclawVersionUtils.MODEL_LIST_COMMAND;

    private static final String MODELS_JSON_PATH = "$HOME/.openclaw/agents/main/models.json";

    private OpenclawModelListUtils() {}

    public static String buildPreferredModelListCommand() {
        return buildPreferredModelListCommand(false);
    }

    public static String buildPreferredModelListCommand(boolean enableTrace) {
        String fallbackCommand = OpenclawVersionUtils.buildModelListCommand(enableTrace);
        return String.join("\n",
            "export LD_LIBRARY_PATH=\"$PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}\"",
            "model_registry_js=\"\"",
            "models_json=\"" + MODELS_JSON_PATH + "\"",
            "probe_script=\"$TMPDIR/botdrop-model-list-$$.mjs\"",
            "cleanup() {",
            "  rm -f \"$probe_script\"",
            "}",
            "trap cleanup EXIT",
            "for candidate in \\",
            "  \"$PREFIX/lib/node_modules/openclaw/node_modules/@mariozechner/pi-coding-agent/dist/core/model-registry.js\" \\",
            "  \"$PREFIX/lib/node_modules/@mariozechner/pi-coding-agent/dist/core/model-registry.js\" \\",
            "  \"$PREFIX/share/botdrop/openclaw-runtime/current/node_modules/@mariozechner/pi-coding-agent/dist/core/model-registry.js\"; do",
            "  if [ -f \"$candidate\" ]; then",
            "    model_registry_js=\"$candidate\"",
            "    break",
            "  fi",
            "done",
            "if [ -x \"$PREFIX/bin/node\" ] && [ -x \"$PREFIX/bin/termux-chroot\" ] && [ -f \"$model_registry_js\" ]; then",
            "  cat > \"$probe_script\" <<'EOF'",
            "const { ModelRegistry } = await import(`file://${process.env.BOTDROP_MODEL_REGISTRY_JS}`);",
            "const fakeAuth = {",
            "  setFallbackResolver() {},",
            "  getOAuthProviders() { return []; },",
            "  get() { return undefined; },",
            "  hasAuth() { return false; },",
            "  getApiKey() { return undefined; },",
            "};",
            "const registry = process.env.BOTDROP_MODELS_JSON",
            "  ? new ModelRegistry(fakeAuth, process.env.BOTDROP_MODELS_JSON)",
            "  : new ModelRegistry(fakeAuth);",
            "const uniqueLines = [...new Set(",
            "  registry.getAll()",
            "    .map((model) => model.provider + '/' + model.id)",
            "    .filter((line) => typeof line === 'string' && line.includes('/')",
            "      && !line.startsWith('undefined/') && !line.endsWith('/undefined'))",
            ")];",
            "if (!uniqueLines.length) {",
            "  process.exit(1);",
            "}",
            "process.stdout.write(uniqueLines.join('\\n'));",
            "process.stdout.write('\\n');",
            "EOF",
            "  export BOTDROP_MODEL_REGISTRY_JS=\"$model_registry_js\"",
            "  if [ -f \"$models_json\" ]; then",
            "    export BOTDROP_MODELS_JSON=\"$models_json\"",
            "  else",
            "    unset BOTDROP_MODELS_JSON",
            "  fi",
            "  if \"$PREFIX/bin/termux-chroot\" \"$PREFIX/bin/node\" \"$probe_script\"; then",
            "    exit 0",
            "  fi",
            "fi",
            fallbackCommand
        );
    }
}
