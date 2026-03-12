package app.botdrop;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ModelSelectorDialogTest {

    @Test
    public void mergeStoredProviderKey_prependsStoredKeyAndDeduplicates() {
        List<String> merged = ModelSelectorDialog.mergeStoredProviderKey(
            Arrays.asList("cached-key", "  stored-key  ", "cached-key"),
            "stored-key",
            8
        );

        assertEquals(Arrays.asList("stored-key", "cached-key"), merged);
    }

    @Test
    public void mergeStoredProviderKey_trimsAndLimitsMergedKeys() {
        List<String> merged = ModelSelectorDialog.mergeStoredProviderKey(
            Arrays.asList(" one ", "two", "three"),
            " four ",
            3
        );

        assertEquals(Arrays.asList("four", "one", "two"), merged);
    }

    @Test
    public void mergeStoredProviderKey_keepsExistingKeysWhenStoredKeyMissing() {
        List<String> merged = ModelSelectorDialog.mergeStoredProviderKey(
            Collections.singletonList("cached-key"),
            "   ",
            8
        );

        assertEquals(Collections.singletonList("cached-key"), merged);
    }
}
