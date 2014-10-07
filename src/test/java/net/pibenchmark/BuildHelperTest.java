package net.pibenchmark;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Unit test for simple App.
 */
public class BuildHelperTest {

    @Test
    public void testExtractNameFromGetter()
    {
        String result = BuildHelper.extractFieldName("getSecondName");
        assertEquals("secondName", result);
    }

    @Test
    public void testExtractNameFromGetter2()
    {
        String result = BuildHelper.extractFieldName("getId");
        assertEquals("id", result);
    }
}
