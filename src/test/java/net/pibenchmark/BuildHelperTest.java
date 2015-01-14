package net.pibenchmark;

import com.google.common.collect.ImmutableMap;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaMethod;
import net.pibenchmark.pojo.FieldType;
import org.apache.maven.plugin.logging.Log;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.runners.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Unit test for simple App.
 */
@RunWith(MockitoJUnitRunner.class)
public class BuildHelperTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private JavaMethod jm;

    @Mock
    private JavaClass jc;

    @Mock
    private Log log;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

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

    @Test
    public void testReturnTypeAsPrimitive()
    {
        when( jm.getReturnType().getGenericFullyQualifiedName() ).thenReturn(String.class.getTypeName());
        when( jc.getCanonicalName() ).thenReturn("no matter");

        FieldType returnType = BuildHelper.getReturnType(jc, jm, ImmutableMap.of(), "number", log);

        assertEquals(String.class.getTypeName(), returnType.getTypeName());
        assertEquals(FieldType.PRIMITIVE, returnType.getTypeKind());
        assertEquals(String.class.getTypeName(), returnType.getOriginalTypeName());
    }

    @Test
    public void testReturnTypeAsPrimitive2()
    {
        when( jm.getReturnType().getGenericFullyQualifiedName() ).thenReturn(boolean.class.getTypeName());
        when( jm.getReturns().getName() ).thenReturn(boolean.class.getSimpleName());
        when( jc.getCanonicalName() ).thenReturn("no matter");

        FieldType returnType = BuildHelper.getReturnType(jc, jm, ImmutableMap.of(), "number", log);

        assertEquals(boolean.class.getSimpleName(), returnType.getTypeName());
        assertEquals(FieldType.PRIMITIVE, returnType.getTypeKind());
        assertEquals(boolean.class.getTypeName(), returnType.getOriginalTypeName());
    }

    @Test
    public void testReturnTypeAsArrayOfBytes()
    {
        when( jm.getReturnType().getGenericFullyQualifiedName() ).thenReturn("byte[]");
        when( jm.getReturns().getName() ).thenReturn("byte");
        when( jc.getCanonicalName() ).thenReturn("no matter");

        FieldType returnType = BuildHelper.getReturnType(jc, jm, ImmutableMap.of(), "number", log);

        //then: type and origin types are presented as simple types, but without trailing []
        assertEquals("byte", returnType.getTypeName());
        assertEquals(FieldType.ARRAY_OF_PRIMITIVES, returnType.getTypeKind());
        assertEquals("byte", returnType.getOriginalTypeName());
    }

    @Test
    public void testReturnTypeAsArrayOfComplexType()
    {
        when( jm.getReturnType().getGenericFullyQualifiedName() ).thenReturn("com.taleo.tee400.Document[]");
        when( jc.getCanonicalName() ).thenReturn("com.taleo.tee400.Requisition");

        FieldType returnType = BuildHelper.getReturnType(jc, jm,
                ImmutableMap.of("com.taleo.tee400.Document", "com.taleo.jpas.DocumentJPA"), "number", log);

        //then: type is according JPA and without trailing []
        assertEquals("com.taleo.jpas.DocumentJPA", returnType.getTypeName());
        assertEquals(FieldType.ARRAY_OF_COMPLEX_TYPES, returnType.getTypeKind());
        assertEquals("com.taleo.tee400.Document", returnType.getOriginalTypeName());
    }

    @Test
    public void testReturnTypeAsComplexType()
    {
        when( jm.getReturnType().getGenericFullyQualifiedName() ).thenReturn("com.taleo.itk.ws.query.Boolean");
        when( jc.getCanonicalName() ).thenReturn("com.taleo.tee400.Requisition");

        FieldType returnType = BuildHelper.getReturnType(jc, jm,
                ImmutableMap.of("com.taleo.itk.ws.query.Boolean", "com.taleo.jpa.BooleanJPA"), "number", log);

        //then: type is according JPA class
        assertEquals("com.taleo.jpa.BooleanJPA", returnType.getTypeName());
        assertEquals(FieldType.COMPLEX_TYPE, returnType.getTypeKind());
        assertEquals("com.taleo.itk.ws.query.Boolean", returnType.getOriginalTypeName());
    }



}
