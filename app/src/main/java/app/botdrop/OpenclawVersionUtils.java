package app.botdrop;

import android.text.TextUtils;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Static utility for OpenClaw version parsing, normalization, and comparison.
 * Shared between AgentSelectionFragment and DashboardActivity.
 */
public final class OpenclawVersionUtils {

    public static final String VERSION_PREFIX = "openclaw@";
    public static final String DEFAULT_NPM_REGISTRY = "https://registry.npmjs.org/";
    public static final String CN_NPM_REGISTRY = "https://registry.npmmirror.com/";
    private static final String BOTDROP_APT_SOURCE_LINE =
        "deb [trusted=yes] https://zhixianio.github.io/botdrop-packages/ stable main";
    private static final String BOTDROP_GITLAB_APT_SOURCE_LINE =
        "deb [trusted=yes] https://lay2dev.gitlab.io/botdrop-packages/ stable main";
    private static final String BOTDROP_APT_SOURCES_LIST = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/apt/sources.list";
    private static final String BOTDROP_APT_SOURCES_LIST_D = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/apt/sources.list.d";
    private static final String BOTDROP_APT_LIST_FILE = BOTDROP_APT_SOURCES_LIST_D + "/botdrop.list";
    public static final int NPM_REGISTRY_CACHE_TTL_SECONDS = 24 * 60 * 60;
    public static final String VERSIONS_COMMAND = buildVersionsCommand();
    public static final String LATEST_VERSION_COMMAND = buildLatestVersionCommand();
    public static final int VERSION_LIST_LIMIT = 20;
    public static final int OPENCLAW_MIN_OLD_SPACE_MB = 1536;
    public static final int OPENCLAW_MAX_OLD_SPACE_MB = 4096;
    private static final int RAM_4_GB_MB = 4 * 1024;
    private static final int RAM_8_GB_MB = 8 * 1024;
    private static final int RAM_10_GB_MB = 10 * 1024;
    private static final int RAM_12_GB_MB = 12 * 1024;
    private static final String OPENCLAW_DEFAULT_OLD_SPACE_ENV =
        "BOTDROP_OPENCLAW_DEFAULT_MAX_OLD_SPACE_MB";
    private static final int OPENCLAW_OLD_SPACE_UP_TO_8_GB_MB = 2048;
    private static final int OPENCLAW_OLD_SPACE_10_GB_MB = 2560;
    private static final int OPENCLAW_OLD_SPACE_12_GB_MB = 3072;

    public interface VersionListCallback {
        void onResult(List<String> versions, String errorMessage);
    }

    private OpenclawVersionUtils() {}

    /**
     * Parse npm JSON output (or line-based fallback) into a sorted list of stable versions.
     */
    public static List<String> parseVersions(String output) {
        List<String> versions = new ArrayList<>();
        if (TextUtils.isEmpty(output)) {
            return versions;
        }

        String trimmed = output.trim();
        try {
            if (trimmed.startsWith("[")) {
                JSONArray json = new JSONArray(trimmed);
                for (int i = 0; i < json.length(); i++) {
                    String token = json.optString(i, null);
                    String normalized = normalizeForSort(token);
                    if (isStableVersion(normalized)) {
                        versions.add(normalized);
                    }
                }
            }
        } catch (Exception ignored) {
        }

        if (!versions.isEmpty()) {
            return sortAndLimit(versions);
        }

        String[] lines = trimmed.split("\\r?\\n");
        for (String line : lines) {
            String normalized = normalizeForSort(line);
            if (isStableVersion(normalized)) {
                versions.add(normalized);
            }
        }
        return sortAndLimit(versions);
    }

    /**
     * Build a fallback list with "latest" and the current version.
     */
    public static List<String> buildFallback(String currentVersion) {
        List<String> fallback = new ArrayList<>();
        fallback.add("latest");
        String current = normalizeForSort(currentVersion);
        if (!TextUtils.isEmpty(current)) {
            fallback.add(current);
        }
        return sortAndLimit(fallback);
    }

    public static String buildVersionsCommand() {
        return buildVersionEnvironmentPrepCommand() + buildNpmCommandPrefix() + "npm view openclaw versions --json";
    }

    public static String buildLatestVersionCommand() {
        return "set -o pipefail; " + buildNpmCommandPrefix()
            + "npm view openclaw version 2>/dev/null | tail -1 | tr -d '[:space:]'";
    }

    public static String buildNpmInstallCommand(String packageSpec) {
        String safePackage = shellQuoteSingle(TextUtils.isEmpty(packageSpec) ? "openclaw@latest" : packageSpec);
        return buildNpmCommandPrefix() + "npm install -g " + safePackage + " --ignore-scripts --force";
    }

    public static String buildNpmInstallCommand(String packageSpec, int oldSpaceMb) {
        String safePackage = shellQuoteSingle(TextUtils.isEmpty(packageSpec) ? "openclaw@latest" : packageSpec);
        return buildNpmCommandPrefix(oldSpaceMb) + "npm install -g " + safePackage + " --ignore-scripts --force";
    }

    public static int recommendOpenclawOldSpaceMb(long totalRamMb) {
        if (totalRamMb <= RAM_4_GB_MB) {
            return OPENCLAW_MIN_OLD_SPACE_MB;
        }
        if (totalRamMb <= RAM_8_GB_MB) {
            return OPENCLAW_OLD_SPACE_UP_TO_8_GB_MB;
        }
        if (totalRamMb <= RAM_10_GB_MB) {
            return OPENCLAW_OLD_SPACE_10_GB_MB;
        }
        if (totalRamMb <= RAM_12_GB_MB) {
            return OPENCLAW_OLD_SPACE_12_GB_MB;
        }
        return OPENCLAW_MAX_OLD_SPACE_MB;
    }

    public static String buildOpenclawNodeOptions(String existingOptions, long totalRamMb) {
        String options = normalizeNodeOptions(existingOptions);

        if (!containsNodeOption(options, "--dns-result-order=")) {
            options = appendNodeOption(options, "--dns-result-order=ipv4first");
        }
        if (!containsNodeOption(options, "--max-old-space-size=")) {
            options = appendNodeOption(options,
                "--max-old-space-size=" + recommendOpenclawOldSpaceMb(totalRamMb));
        }

        return options;
    }

    /**
     * Build NODE_OPTIONS export with a pre-computed heap size from Java layer.
     * This avoids relying on /proc/meminfo inside proot/chroot where it may be unreliable.
     */
    public static String buildNodeOptionsExportCommand(int oldSpaceMb) {
        return "export " + OPENCLAW_DEFAULT_OLD_SPACE_ENV + "=" + oldSpaceMb + "\n"
            + buildNodeOptionsExportCommand();
    }

    public static String buildNodeOptionsExportCommand() {
        return "botdrop_set_openclaw_node_options() {\n"
            + "  node_options=\"${NODE_OPTIONS:-}\"\n"
            + "  old_space_mb=\"${BOTDROP_OPENCLAW_MAX_OLD_SPACE_MB:-${"
            + OPENCLAW_DEFAULT_OLD_SPACE_ENV + ":-}}\"\n"
            + "  case \"$old_space_mb\" in\n"
            + "    ''|*[!0-9]*) old_space_mb='' ;;\n"
            + "  esac\n"
            + "  if [ -z \"$old_space_mb\" ]; then\n"
            + "    mem_total_kb=\"$(awk '/MemTotal:/ {print $2; exit}' /proc/meminfo 2>/dev/null)\"\n"
            + "    case \"$mem_total_kb\" in\n"
            + "      ''|*[!0-9]*) mem_total_kb=0 ;;\n"
            + "    esac\n"
            + "    if [ \"$mem_total_kb\" -le 0 ]; then\n"
            + "      old_space_mb=" + OPENCLAW_MIN_OLD_SPACE_MB + "\n"
            + "    else\n"
            + "      mem_total_mb=$((mem_total_kb / 1024))\n"
            + "      if [ \"$mem_total_mb\" -le " + RAM_4_GB_MB + " ]; then\n"
            + "        old_space_mb=" + OPENCLAW_MIN_OLD_SPACE_MB + "\n"
            + "      elif [ \"$mem_total_mb\" -le " + RAM_8_GB_MB + " ]; then\n"
            + "        old_space_mb=" + OPENCLAW_OLD_SPACE_UP_TO_8_GB_MB + "\n"
            + "      elif [ \"$mem_total_mb\" -le " + RAM_10_GB_MB + " ]; then\n"
            + "        old_space_mb=" + OPENCLAW_OLD_SPACE_10_GB_MB + "\n"
            + "      elif [ \"$mem_total_mb\" -le " + RAM_12_GB_MB + " ]; then\n"
            + "        old_space_mb=" + OPENCLAW_OLD_SPACE_12_GB_MB + "\n"
            + "      else\n"
            + "        old_space_mb=" + OPENCLAW_MAX_OLD_SPACE_MB + "\n"
            + "      fi\n"
            + "    fi\n"
            + "  fi\n"
            + "  case \"$node_options\" in\n"
            + "    *--dns-result-order=*) ;;\n"
            + "    *) node_options=\"${node_options:+$node_options }--dns-result-order=ipv4first\" ;;\n"
            + "  esac\n"
            + "  case \"$node_options\" in\n"
            + "    *--max-old-space-size=*) ;;\n"
            + "    *) node_options=\"${node_options:+$node_options }--max-old-space-size=$old_space_mb\" ;;\n"
            + "  esac\n"
            + "  export NODE_OPTIONS=\"$node_options\"\n"
            + "}\n"
            + "botdrop_set_openclaw_node_options\n";
    }

    private static String buildVersionEnvironmentPrepCommand() {
        return
            "mkdir -p " + BOTDROP_APT_SOURCES_LIST_D + "\n" +
            "printf '%s\\n' '" + BOTDROP_APT_SOURCE_LINE + "' > " + BOTDROP_APT_LIST_FILE + "\n" +
            "printf '%s\\n' '" + BOTDROP_GITLAB_APT_SOURCE_LINE + "' >> " + BOTDROP_APT_LIST_FILE + "\n" +
            "cp " + BOTDROP_APT_LIST_FILE + " " + BOTDROP_APT_SOURCES_LIST + "\n" +
            "for f in " + BOTDROP_APT_SOURCES_LIST_D + "/*.list; do\n" +
            "    if [ -f \"$f\" ] && [ \"$f\" != \"" + BOTDROP_APT_LIST_FILE + "\" ]; then\n" +
            "        rm -f \"$f\"\n" +
            "    fi\n" +
            "done\n" +
            "chmod +x $PREFIX/bin/* 2>/dev/null || true\n" +
            "chmod +x $PREFIX/lib/node_modules/.bin/* 2>/dev/null || true\n" +
            "chmod +x $PREFIX/lib/node_modules/npm/bin/* 2>/dev/null || true\n";
    }

    public static String buildNpmCommandPrefix() {
        return buildNpmRegistryResolverFunction()
            + "NPM_CONFIG_REGISTRY=\"$(botdrop_resolve_npm_registry)\"\n"
            + "export NPM_CONFIG_REGISTRY\n"
            + buildNodeOptionsExportCommand();
    }

    public static String buildNpmCommandPrefix(int oldSpaceMb) {
        return buildNpmRegistryResolverFunction()
            + "NPM_CONFIG_REGISTRY=\"$(botdrop_resolve_npm_registry)\"\n"
            + "export NPM_CONFIG_REGISTRY\n"
            + buildNodeOptionsExportCommand(oldSpaceMb);
    }

    /**
     * Filter and normalize a version list, keeping only "latest" and stable versions.
     */
    public static List<String> normalizeVersionList(List<String> versions) {
        if (versions == null || versions.isEmpty()) {
            return new ArrayList<>();
        }

        List<String> normalized = new ArrayList<>();
        for (String version : versions) {
            String normalizedVersion = normalizeForSort(version);
            if (!TextUtils.isEmpty(normalizedVersion)
                && (TextUtils.equals("latest", normalizedVersion) || isStableVersion(normalizedVersion))) {
                normalized.add(normalizedVersion);
            }
        }
        return sortAndLimit(normalized);
    }

    /**
     * Returns "openclaw@version" format for installation.
     */
    public static String normalizeInstallVersion(String version) {
        String normalized = normalizeForSort(version);
        if (TextUtils.isEmpty(normalized)) {
            return null;
        }
        if (TextUtils.equals("latest", normalized)) {
            return VERSION_PREFIX + "latest";
        }
        return VERSION_PREFIX + normalized;
    }

    /**
     * Strip "openclaw@", "v" prefixes and quotes for bare version comparison.
     */
    public static String normalizeForSort(String version) {
        if (TextUtils.isEmpty(version)) {
            return null;
        }
        String v = version.trim();
        if (TextUtils.isEmpty(v)) {
            return null;
        }
        if (v.startsWith("[")) {
            v = v.substring(1).trim();
        }
        while (v.endsWith(",")) {
            v = v.substring(0, v.length() - 1).trim();
        }
        if (v.endsWith("]")) {
            v = v.substring(0, v.length() - 1).trim();
        }
        v = v.replace("\"", "").replace("'", "").trim();
        if (TextUtils.isEmpty(v)) {
            return null;
        }
        if (v.startsWith(VERSION_PREFIX)) {
            v = v.substring(VERSION_PREFIX.length());
        }
        v = v.trim();
        if (v.startsWith("v")) {
            v = v.substring(1).trim();
        }
        if (TextUtils.isEmpty(v)) {
            return null;
        }
        return v;
    }

    /**
     * Returns true if the version string is a stable semver (no pre-release suffix).
     */
    public static boolean isStableVersion(String version) {
        if (TextUtils.isEmpty(version)) {
            return false;
        }
        if (TextUtils.equals("latest", version)) {
            return false;
        }
        if (version.contains("-") || version.contains("+")) {
            return false;
        }
        try {
            OpenClawUpdateChecker.parseSemver(version);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Deduplicate, sort descending by semver, and limit to VERSION_LIST_LIMIT.
     */
    public static List<String> sortAndLimit(List<String> versions) {
        List<String> unique = new ArrayList<>();
        for (String version : versions) {
            String normalized = normalizeForSort(version);
            if (!TextUtils.isEmpty(normalized) && !unique.contains(normalized)) {
                unique.add(normalized);
            }
        }

        Collections.sort(unique, OpenclawVersionUtils::compareDesc);
        if (unique.size() > VERSION_LIST_LIMIT) {
            unique = new ArrayList<>(unique.subList(0, VERSION_LIST_LIMIT));
        }
        return unique;
    }

    /**
     * Compare two version strings in descending order. "latest" sorts first.
     */
    public static int compareDesc(String a, String b) {
        if (TextUtils.equals(a, b)) {
            return 0;
        }
        if (TextUtils.equals("latest", a)) {
            return -1;
        }
        if (TextUtils.equals("latest", b)) {
            return 1;
        }
        try {
            int[] av = OpenClawUpdateChecker.parseSemver(a);
            int[] bv = OpenClawUpdateChecker.parseSemver(b);
            for (int i = 0; i < 3; i++) {
                if (av[i] != bv[i]) {
                    return Integer.compare(bv[i], av[i]);
                }
            }
            return 0;
        } catch (Exception ignored) {
            return b.compareToIgnoreCase(a);
        }
    }

    private static String buildNpmRegistryResolverFunction() {
        return "botdrop_resolve_npm_registry() {\n"
            + "  default_registry=\"" + DEFAULT_NPM_REGISTRY + "\"\n"
            + "  cn_registry=\"" + CN_NPM_REGISTRY + "\"\n"
            + "  cache_file=\"$HOME/.botdrop_npm_registry_cache\"\n"
            + "  cache_ttl_seconds=" + NPM_REGISTRY_CACHE_TTL_SECONDS + "\n"
            + "  gateway=\"\"\n"
            + "  resolved=\"\"\n"
            + "  country=\"\"\n"
            + "  npmjs_probe=\"\"\n"
            + "  npmmirror_probe=\"\"\n"
            + "  resolved_probe=\"\"\n"
            + "  cache_mid=0\n"
            + "  now=\"$(date +%s 2>/dev/null || echo 0)\"\n"
            + "  cache_gateway=\"\"\n"
            + "  cache_expiry=\"\"\n"
            + "  cache_registry=\"\"\n"
            + "\n"
            + "  if [ -n \"$BOTDROP_NPM_REGISTRY\" ]; then\n"
            + "    case \"$BOTDROP_NPM_REGISTRY\" in\n"
            + "      http://*|https://*)\n"
            + "        echo \"$BOTDROP_NPM_REGISTRY\"\n"
            + "        ;;\n"
            + "      *)\n"
            + "        echo \"$default_registry\"\n"
            + "        ;;\n"
            + "    esac\n"
            + "    return 0\n"
            + "  fi\n"
            + "\n"
            + "  if command -v ip >/dev/null 2>&1; then\n"
            + "    gateway=\"$(ip route 2>/dev/null | awk '/^default/ {print $3; exit}')\"\n"
            + "  fi\n"
            + "  if [ -z \"$gateway\" ]; then\n"
            + "    gateway=\"unknown\"\n"
            + "  fi\n"
            + "\n"
            + "  if [ -f \"$cache_file\" ]; then\n"
            + "    cache_gateway=\"$(awk -F= '/^gateway=/{print $2; exit}' \"$cache_file\")\"\n"
            + "    cache_expiry=\"$(awk -F= '/^expiry=/{print $2; exit}' \"$cache_file\")\"\n"
            + "    cache_registry=\"$(awk -F= '/^registry=/{print $2; exit}' \"$cache_file\")\"\n"
            + "    case \"$cache_registry\" in\n"
            + "      \"$default_registry\"|\"$cn_registry\")\n"
            + "        ;;\n"
            + "      *)\n"
            + "        cache_registry=\"\"\n"
            + "        ;;\n"
            + "    esac\n"
            + "    case \"$cache_expiry\" in\n"
            + "      ''|*[!0-9]*)\n"
            + "        cache_expiry=0\n"
            + "        ;;\n"
            + "    esac\n"
            + "    if [ \"$gateway\" = \"$cache_gateway\" ] && [ -n \"$cache_registry\" ] &&\n"
            + "      [ \"$cache_expiry\" -ge \"$now\" ]; then\n"
            + "      resolved=\"$cache_registry\"\n"
            + "    fi\n"
            + "  fi\n"
            + "\n"
            + "  if [ -n \"$resolved\" ]; then\n"
            + "    # Re-validate only when cache is past half its TTL to avoid extra latency on fresh entries.\n"
            + "    cache_mid=$((cache_expiry - cache_ttl_seconds / 2))\n"
            + "    if [ \"$now\" -ge \"$cache_mid\" ]; then\n"
            + "      if command -v curl >/dev/null 2>&1; then\n"
            + "        resolved_probe=\"$(curl -m 2 -o /dev/null -s -w '%{http_code}' \"${resolved}openclaw\" 2>/dev/null)\"\n"
            + "      elif command -v wget >/dev/null 2>&1; then\n"
            + "        wget -q -T 2 -t 1 --spider \"${resolved}openclaw\" >/dev/null 2>&1 && resolved_probe=200\n"
            + "      fi\n"
            + "      if [ \"$resolved_probe\" != \"200\" ]; then\n"
            + "        resolved=\"\"\n"
            + "        resolved_probe=\"\"\n"
            + "        cache_registry=\"\"\n"
            + "      fi\n"
            + "    fi\n"
            + "  fi\n"
            + "\n"
            + "  if [ -z \"$resolved\" ]; then\n"
            + "    # Prefer direct registry reachability probing over GeoIP.\n"
            + "    # - CN networks often fail/slow on npmjs but work on npmmirror.\n"
            + "    # - This avoids relying on third-party GeoIP endpoints.\n"
            + "    if command -v curl >/dev/null 2>&1; then\n"
            + "      npmjs_probe=\"$(curl -m 2 -o /dev/null -s -w '%{http_code}' \"${default_registry}openclaw\" 2>/dev/null)\"\n"
            + "      npmmirror_probe=\"$(curl -m 2 -o /dev/null -s -w '%{http_code}' \"${cn_registry}openclaw\" 2>/dev/null)\"\n"
            + "    elif command -v wget >/dev/null 2>&1; then\n"
            + "      wget -q -T 2 -t 1 --spider \"${default_registry}openclaw\" >/dev/null 2>&1 && npmjs_probe=200\n"
            + "      wget -q -T 2 -t 1 --spider \"${cn_registry}openclaw\" >/dev/null 2>&1 && npmmirror_probe=200\n"
            + "    fi\n"
            + "\n"
            + "    if [ \"$npmmirror_probe\" = \"200\" ] && [ \"$npmjs_probe\" != \"200\" ]; then\n"
            + "      resolved=\"$cn_registry\"\n"
            + "    elif [ \"$npmjs_probe\" = \"200\" ]; then\n"
            + "      resolved=\"$default_registry\"\n"
            + "    elif [ \"$npmmirror_probe\" = \"200\" ]; then\n"
            + "      resolved=\"$cn_registry\"\n"
            + "    else\n"
            + "      # Fallback to previous GeoIP heuristic only when probes are unavailable.\n"
            + "      if command -v curl >/dev/null 2>&1; then\n"
            + "        country=\"$(curl -m 2 -fsSL https://ipinfo.io/country 2>/dev/null | tr -d '\\r\\n' | tr '[:lower:]' '[:upper:]')\"\n"
            + "      elif command -v wget >/dev/null 2>&1; then\n"
            + "        country=\"$(wget -qO- --timeout=2 --tries=1 https://ipinfo.io/country 2>/dev/null | tr -d '\\r\\n' | tr '[:lower:]' '[:upper:]')\"\n"
            + "      fi\n"
            + "      if [ \"$country\" = \"CN\" ]; then\n"
            + "        resolved=\"$cn_registry\"\n"
            + "      else\n"
            + "        resolved=\"$default_registry\"\n"
            + "      fi\n"
            + "    fi\n"
            + "\n"
            + "    {\n"
            + "      echo \"gateway=$gateway\"\n"
            + "      echo \"expiry=$((now + cache_ttl_seconds))\"\n"
            + "      echo \"registry=$resolved\"\n"
            + "    } > \"$cache_file\"\n"
            + "  fi\n"
            + "\n"
            + "  if [ -z \"$resolved\" ]; then\n"
            + "    resolved=\"$default_registry\"\n"
            + "  fi\n"
            + "  printf \"gateway=%s\\nregistry=%s\\n\" \"$gateway\" \"$resolved\" > \"$HOME/.botdrop_last_npm_registry\"\n"
            + "  echo \"$resolved\"\n"
            + "}\n";
    }

    private static String shellQuoteSingle(String value) {
        if (TextUtils.isEmpty(value)) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String normalizeNodeOptions(String options) {
        if (TextUtils.isEmpty(options)) {
            return "";
        }
        return options.trim().replaceAll("\\s+", " ");
    }

    private static boolean containsNodeOption(String options, String prefix) {
        if (TextUtils.isEmpty(options) || TextUtils.isEmpty(prefix)) {
            return false;
        }
        String[] tokens = options.split("\\s+");
        for (String token : tokens) {
            if (token.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String appendNodeOption(String options, String option) {
        if (TextUtils.isEmpty(options)) {
            return option;
        }
        return options + " " + option;
    }
}
