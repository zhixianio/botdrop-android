package app.botdrop;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class OpenclawVersionUtilsTest {

    @Test
    public void testParseVersions_jsonArray() {
        String json = "[\"1.0.0\", \"1.1.0\", \"2.0.0-beta.1\", \"1.2.0\"]";
        List<String> result = OpenclawVersionUtils.parseVersions(json);
        // Should exclude pre-release, sort desc
        assertFalse(result.isEmpty());
        assertTrue(result.contains("1.2.0"));
        assertTrue(result.contains("1.1.0"));
        assertTrue(result.contains("1.0.0"));
        assertFalse(result.contains("2.0.0-beta.1"));
        assertEquals("1.2.0", result.get(0));
    }

    @Test
    public void testParseVersions_emptyInput() {
        assertTrue(OpenclawVersionUtils.parseVersions(null).isEmpty());
        assertTrue(OpenclawVersionUtils.parseVersions("").isEmpty());
        assertTrue(OpenclawVersionUtils.parseVersions("  ").isEmpty());
    }

    @Test
    public void testParseVersions_lineBasedFallback() {
        String lines = "1.0.0\n1.1.0\n1.2.0";
        List<String> result = OpenclawVersionUtils.parseVersions(lines);
        assertFalse(result.isEmpty());
        assertEquals("1.2.0", result.get(0));
    }

    @Test
    public void testBuildFallback_withCurrentVersion() {
        List<String> result = OpenclawVersionUtils.buildFallback("2026.2.6");
        assertTrue(result.contains("latest"));
        assertTrue(result.contains("2026.2.6"));
    }

    @Test
    public void testBuildFallback_nullCurrentVersion() {
        List<String> result = OpenclawVersionUtils.buildFallback(null);
        assertTrue(result.contains("latest"));
        assertEquals(1, result.size());
    }

    @Test
    public void testNormalizeVersionList_filtersPreRelease() {
        List<String> input = Arrays.asList("1.0.0", "2.0.0-alpha", "1.1.0", "latest");
        List<String> result = OpenclawVersionUtils.normalizeVersionList(input);
        assertTrue(result.contains("1.1.0"));
        assertTrue(result.contains("1.0.0"));
        assertTrue(result.contains("latest"));
        assertFalse(result.contains("2.0.0-alpha"));
    }

    @Test
    public void testNormalizeInstallVersion() {
        assertEquals("openclaw@1.2.3", OpenclawVersionUtils.normalizeInstallVersion("1.2.3"));
        assertEquals("openclaw@latest", OpenclawVersionUtils.normalizeInstallVersion("latest"));
        assertEquals("openclaw@1.2.3", OpenclawVersionUtils.normalizeInstallVersion("openclaw@1.2.3"));
        assertEquals("openclaw@1.2.3", OpenclawVersionUtils.normalizeInstallVersion("v1.2.3"));
        assertNull(OpenclawVersionUtils.normalizeInstallVersion(null));
        assertNull(OpenclawVersionUtils.normalizeInstallVersion(""));
    }

    @Test
    public void testNormalizeForSort() {
        assertEquals("1.2.3", OpenclawVersionUtils.normalizeForSort("openclaw@1.2.3"));
        assertEquals("1.2.3", OpenclawVersionUtils.normalizeForSort("v1.2.3"));
        assertEquals("1.2.3", OpenclawVersionUtils.normalizeForSort("1.2.3"));
        assertEquals("latest", OpenclawVersionUtils.normalizeForSort("latest"));
        assertNull(OpenclawVersionUtils.normalizeForSort(null));
        assertNull(OpenclawVersionUtils.normalizeForSort(""));
        assertNull(OpenclawVersionUtils.normalizeForSort("openclaw@"));
    }

    @Test
    public void testIsStableVersion() {
        assertTrue(OpenclawVersionUtils.isStableVersion("1.0.0"));
        assertTrue(OpenclawVersionUtils.isStableVersion("2026.2.6"));
        assertFalse(OpenclawVersionUtils.isStableVersion("1.0.0-beta"));
        assertFalse(OpenclawVersionUtils.isStableVersion("1.0.0+build"));
        assertFalse(OpenclawVersionUtils.isStableVersion("latest"));
        assertFalse(OpenclawVersionUtils.isStableVersion(null));
        assertFalse(OpenclawVersionUtils.isStableVersion(""));
    }

    @Test
    public void testSortAndLimit_deduplicates() {
        List<String> input = Arrays.asList("1.0.0", "1.0.0", "1.1.0");
        List<String> result = OpenclawVersionUtils.sortAndLimit(input);
        assertEquals(2, result.size());
        assertEquals("1.1.0", result.get(0));
    }

    @Test
    public void testSortAndLimit_latestFirst() {
        List<String> input = Arrays.asList("1.0.0", "latest", "2.0.0");
        List<String> result = OpenclawVersionUtils.sortAndLimit(input);
        assertEquals("latest", result.get(0));
        assertEquals("2.0.0", result.get(1));
        assertEquals("1.0.0", result.get(2));
    }

    @Test
    public void testCompareDesc_ordering() {
        assertTrue(OpenclawVersionUtils.compareDesc("2.0.0", "1.0.0") < 0);
        assertTrue(OpenclawVersionUtils.compareDesc("1.0.0", "2.0.0") > 0);
        assertEquals(0, OpenclawVersionUtils.compareDesc("1.0.0", "1.0.0"));
        assertTrue(OpenclawVersionUtils.compareDesc("latest", "1.0.0") < 0);
        assertTrue(OpenclawVersionUtils.compareDesc("1.0.0", "latest") > 0);
    }

    @Test
    public void testRecommendOpenclawOldSpaceMb_clampsAndScales() {
        assertEquals(1536, OpenclawVersionUtils.recommendOpenclawOldSpaceMb(0));
        assertEquals(1536, OpenclawVersionUtils.recommendOpenclawOldSpaceMb(4096));
        assertEquals(2048, OpenclawVersionUtils.recommendOpenclawOldSpaceMb(6144));
        assertEquals(2048, OpenclawVersionUtils.recommendOpenclawOldSpaceMb(8192));
        assertEquals(2560, OpenclawVersionUtils.recommendOpenclawOldSpaceMb(10240));
        assertEquals(3072, OpenclawVersionUtils.recommendOpenclawOldSpaceMb(12288));
        assertEquals(4096, OpenclawVersionUtils.recommendOpenclawOldSpaceMb(32768));
    }

    @Test
    public void testBuildOpenclawNodeOptions_addsDnsAndHeapLimit() {
        String options = OpenclawVersionUtils.buildOpenclawNodeOptions("", 8192);

        assertTrue(options.contains("--dns-result-order=ipv4first"));
        assertTrue(options.contains("--max-old-space-size=2048"));
    }

    @Test
    public void testBuildOpenclawNodeOptions_preservesExplicitHeapLimit() {
        String options = OpenclawVersionUtils.buildOpenclawNodeOptions(
            "--trace-warnings --max-old-space-size=6144",
            8192
        );

        assertTrue(options.contains("--dns-result-order=ipv4first"));
        assertTrue(options.contains("--trace-warnings"));
        assertTrue(options.contains("--max-old-space-size=6144"));
        assertFalse(options.contains("--max-old-space-size=2048"));
    }

    @Test
    public void testBuildNodeOptionsExportCommand_containsHeapSetup() {
        String script = OpenclawVersionUtils.buildNodeOptionsExportCommand();

        assertTrue(script.contains("BOTDROP_OPENCLAW_MAX_OLD_SPACE_MB"));
        assertTrue(script.contains("--dns-result-order=ipv4first"));
        assertTrue(script.contains("--max-old-space-size="));
    }

    @Test
    public void testBuildNodeOptionsExportCommand_withPrecomputedOldSpace() {
        String script = OpenclawVersionUtils.buildNodeOptionsExportCommand(2048);

        assertTrue(script.startsWith("export BOTDROP_OPENCLAW_DEFAULT_MAX_OLD_SPACE_MB=2048\n"));
        assertTrue(script.contains(
            "old_space_mb=\"${BOTDROP_OPENCLAW_MAX_OLD_SPACE_MB:-${BOTDROP_OPENCLAW_DEFAULT_MAX_OLD_SPACE_MB:-}}\""));
        assertTrue(script.contains("--dns-result-order=ipv4first"));
        assertTrue(script.contains("--max-old-space-size="));
    }

    @Test
    public void testBuildNpmInstallCommand_withPrecomputedOldSpace() {
        String command = OpenclawVersionUtils.buildNpmInstallCommand("openclaw@latest", 3072);

        assertTrue(command.contains("export BOTDROP_OPENCLAW_DEFAULT_MAX_OLD_SPACE_MB=3072\n"));
        assertTrue(command.contains("npm install -g 'openclaw@latest' --ignore-scripts --force"));
    }
}
