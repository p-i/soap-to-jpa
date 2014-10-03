package net.pibenchmark;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Unit test for simple App.
 */
public class BuildJPAFileContentTest {

    @Test
    public void testExtractNameFromGetter()
    {
        String result = BuildJPAFileContent.extractFieldName("getSecondName");
        assertEquals("secondName", result);
    }

    @Test
    public void testExtractNameFromGetter2()
    {
        String result = BuildJPAFileContent.extractFieldName("getId");
        assertEquals("id", result);
    }
}
