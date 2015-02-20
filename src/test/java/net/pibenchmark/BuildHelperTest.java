package net.pibenchmark;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaField;
import com.thoughtworks.qdox.model.JavaMethod;
import net.pibenchmark.pojo.FieldType;
import org.apache.maven.plugin.logging.Log;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Matcher;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit testFiles for simple App.
 */
@RunWith(MockitoJUnitRunner.class)
public class BuildHelperTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private JavaMethod jm;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private JavaClass jc;

    @Mock
    private JavaClass jcParent;

    @Mock
    private JavaClass jcObject;

    @Mock
    private JavaField mockedField;

    @Mock
    private JavaProjectBuilder builder;

    @Mock
    private Log log;

    public BuildHelperTest() {
        MockitoAnnotations.initMocks(this);
    }

    @Before
    public void setUp() throws Exception {
        when( jcObject.getName() ).thenReturn(Object.class.getTypeName());
        when( builder.getClassByName(any(String.class))).thenReturn(jcObject);
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
        when( jm.getReturns() ).thenReturn( jcObject );
        when( jc.getCanonicalName() ).thenReturn("no matter");

        FieldType returnType = BuildHelper.getReturnType(jc, jm, ImmutableMap.of(), "number", log, builder);

        assertEquals(String.class.getTypeName(), returnType.getTypeName());
        assertEquals(FieldType.PRIMITIVE, returnType.getTypeKind());
        assertEquals(String.class.getTypeName(), returnType.getOriginalTypeName());
    }

    @Test
    public void testReturnTypeAsPrimitive2()
    {
        when( jm.getReturnType().getGenericFullyQualifiedName() ).thenReturn(boolean.class.getTypeName());
        when( jm.getReturns().getName() ).thenReturn(boolean.class.getSimpleName());
        when( jm.getReturns() ).thenReturn( jcObject );
        when( jc.getCanonicalName() ).thenReturn("no matter");

        FieldType returnType = BuildHelper.getReturnType(jc, jm, ImmutableMap.of(), "number", log, builder);

        assertEquals(boolean.class.getSimpleName(), returnType.getTypeName());
        assertEquals(FieldType.PRIMITIVE, returnType.getTypeKind());
        assertEquals(boolean.class.getTypeName(), returnType.getOriginalTypeName());
    }

    @Test
    public void testReturnTypeAsArrayOfBytes()
    {
        when( jm.getReturnType().getGenericFullyQualifiedName() ).thenReturn("byte[]");
        when( jm.getReturns().getName() ).thenReturn("byte");
        when( jm.getReturns() ).thenReturn( jcObject );
        when( jc.getCanonicalName() ).thenReturn("no matter");

        FieldType returnType = BuildHelper.getReturnType(jc, jm, ImmutableMap.of(), "number", log, builder);

        //then: type and origin types are presented as simple types, but without trailing []
        assertEquals("byte", returnType.getTypeName());
        assertEquals(FieldType.ARRAY_OF_PRIMITIVES, returnType.getTypeKind());
        assertEquals("byte", returnType.getOriginalTypeName());
    }

    @Test
    public void testReturnTypeAsCollection()
    {
        when( jcObject.getFields() ).thenReturn(Lists.newArrayList());
        when( jm.getReturnType().getGenericFullyQualifiedName() ).thenReturn("java.util.List<com.pi.taleo.realtime.api.stubs.Offer>");
        when( jm.getReturns().getName() ).thenReturn("List");
        when( jm.getReturns() ).thenReturn( jcObject );
        when( jc.getCanonicalName() ).thenReturn("no matter");
        when( jc.getPackage().getName() ).thenReturn("com.pi.taleo.realtime.api.stubs");

        FieldType returnType = BuildHelper.getReturnType(jc, jm,
                ImmutableMap.of("com.pi.taleo.realtime.api.stubs.Offer", "com.taleo.jpas.OfferJPA"), "offers", log, builder);

        //then: type and origin types are presented as complex types
        assertEquals("com.taleo.jpas.OfferJPA", returnType.getTypeName());
        assertEquals("com.pi.taleo.realtime.api.stubs.Offer", returnType.getOriginalTypeName());
        assertEquals("Offer", returnType.getOriginalTypeSimpleName());
        assertEquals(FieldType.COLLECTION, returnType.getTypeKind());
    }

    @Test
    public void testReturnTypeAsCollectionWithInnerClass()
    {
        when( jcObject.getFields() ).thenReturn(Lists.newArrayList());
        when( jm.getReturnType().getGenericFullyQualifiedName() ).thenReturn("java.util.List<com.pi.taleo.realtime.api.stubs.Offer.Inner>");
        when( jm.getReturns().getName() ).thenReturn("List");
        when( jm.getReturns() ).thenReturn( jcObject );
        when( jc.getCanonicalName() ).thenReturn("no matter");
        when( jc.getPackage().getName() ).thenReturn("com.pi.taleo.realtime.api.stubs");

        FieldType returnType = BuildHelper.getReturnType(jc, jm,
                ImmutableMap.of("com.pi.taleo.realtime.api.stubs.Offer.Inner", "com.taleo.jpas.Offer.InnerJPA"), "offers", log, builder);

        //then: type and origin types are presented as complex types
        assertEquals("com.taleo.jpas.Offer.InnerJPA", returnType.getTypeName());
        assertEquals("com.pi.taleo.realtime.api.stubs.Offer.Inner", returnType.getOriginalTypeName());
        assertEquals("Inner", returnType.getOriginalTypeSimpleName());
        assertEquals(FieldType.COLLECTION, returnType.getTypeKind());
    }

    @Test
    public void testReturnTypeAsArrayOfComplexType()
    {
        when( jm.getReturnType().getGenericFullyQualifiedName() ).thenReturn("com.taleo.tee400.Document[]");
        when( jm.getReturns() ).thenReturn( jcObject );
        when( jc.getCanonicalName() ).thenReturn("com.taleo.tee400.Requisition");

        FieldType returnType = BuildHelper.getReturnType(jc, jm,
                ImmutableMap.of("com.taleo.tee400.Document", "com.taleo.jpas.DocumentJPA"), "number", log, builder);

        //then: type is according JPA and without trailing []
        assertEquals("com.taleo.jpas.DocumentJPA", returnType.getTypeName());
        assertEquals(FieldType.ARRAY_OF_COMPLEX_TYPES, returnType.getTypeKind());
        assertEquals("com.taleo.tee400.Document", returnType.getOriginalTypeName());
    }

    @Test
    public void testReturnTypeAsComplexType()
    {
        when( jm.getReturnType().getGenericFullyQualifiedName() ).thenReturn("com.taleo.itk.ws.query.Boolean");
        when( jm.getReturns().getFullyQualifiedName() ).thenReturn("com.taleo.itk.ws.query.Boolean");
        when( jcObject.getFullyQualifiedName() ).thenReturn(Object.class.getTypeName());
        when( jm.getReturns() ).thenReturn( jcObject );
        when( jc.getCanonicalName() ).thenReturn("com.taleo.tee400.Requisition");
        when( jc.getFullyQualifiedName() ).thenReturn("com.taleo.tee400.Requisition");

        FieldType returnType = BuildHelper.getReturnType(jc, jm,
                ImmutableMap.of("com.taleo.itk.ws.query.Boolean", "com.taleo.jpa.BooleanJPA"), "number", log, builder);

        //then: type is according JPA class
        assertEquals("com.taleo.jpa.BooleanJPA", returnType.getTypeName());
        assertEquals(FieldType.COMPLEX_TYPE, returnType.getTypeKind());
        assertEquals("com.taleo.itk.ws.query.Boolean", returnType.getOriginalTypeName());
    }

    @Test
    public void testCollectAllFields() {

        /*
            situation, where we want have a class with no fields, extending
            another parent class that has field "number".

            Verify, that "recursivelyCollectAllFields()" method detects
            that child class has "number" field.
         */
        when( mockedField.getName() ).thenReturn("number");
        when( jc.getSuperJavaClass().getFields()).thenReturn( ImmutableList.of(mockedField) );
        when( jc.getFields() ).thenReturn(Lists.newArrayList());

        // collect all the fields (including parent ones)
        final List<JavaField> javaFields = BuildHelper.collectParentFields(jc);

        assertNotNull(javaFields);
        assertTrue(javaFields.stream().anyMatch((field) -> field.getName().equals("number")));
        assertTrue(javaFields.stream().noneMatch((field) -> field.getName().equals("nonExistingField")));
    }

    /**
     * situation, where we want have a class with no fields, extending
     * another parent class that has field "number".
     *
     * Verify, that "recursivelyCollectAllFields()" method detects
     * that child class has "number" field.
     *
     * @throws FileNotFoundException
     */
    @Test
    public void testCheckForID() throws FileNotFoundException {

        // given: A class, that has no fields, but it grand-parent has "number" field
        final JavaProjectBuilder builder = new JavaProjectBuilder();
        builder.addSource(new FileReader(this.getSourceFile("A.java")));
        final JavaClass jc = builder.getClassByName("A");

        // when: we recursively looking up to the ID field
        final boolean isFound = BuildHelper.recursivelyLookupForIDfield(jc, "number");

        // then: we successfully find it
        assertTrue(isFound);
    }

    @Test
    public void testCheckForUnexistingField() throws FileNotFoundException {

        // given: A class, that has no fields, but it grand-parent has "number" field
        final JavaProjectBuilder builder = new JavaProjectBuilder();
        builder.addSource(new FileReader(this.getSourceFile("A.java")));

        // when: we recursively looking up to the ID field
        final JavaClass jc = builder.getClassByName("A");
        final boolean isFound = BuildHelper.recursivelyLookupForIDfield(jc, "anyUnexistingFiled");

        // then: we find it
        assertFalse(isFound);
    }

    @Test
    public void testCheckForIDFirstClass() throws FileNotFoundException {

        // given: A class, that has no fields, but it grand-parent has "number" field
        final JavaProjectBuilder builder = new JavaProjectBuilder();
        builder.addSource(new FileReader(this.getSourceFile("D.java")));

        // when: we recursively looking up to the ID field
        final JavaClass jc = builder.getClassByName("D");
        final boolean isFound = BuildHelper.recursivelyLookupForIDfield(jc, "number");

        // then: we successfully find it
        assertTrue(isFound);
    }

    @Test
    public void testCheckForUnexistingFieldFirstClass() throws FileNotFoundException {

        // given: A class, that has no fields, but it grand-parent has "number" field
        final JavaProjectBuilder builder = new JavaProjectBuilder();
        builder.addSource(new FileReader(this.getSourceFile("D.java")));

        // when: we recursively looking up to the ID field
        final JavaClass jc = builder.getClassByName("D");
        final boolean isFound = BuildHelper.recursivelyLookupForIDfield(jc, "anyUnexistingFiled");

        // then: we don't find it
        assertFalse(isFound);
    }

    @Test
    public void checkingNullForPrimitivesDoesNotThrowException() {
        final double testValue = 0;
        assertTrue(null != (java.lang.Object)testValue);
    }

    @Test
    public void testReapitalyze() throws Exception {

        final String result = BuildHelper.recapitalizeRemovingUnderscores("regex_like");
        assert result.equals("regexLike");

    }


    @Test
    public void testReapitalyzeWithoutUnderscore() throws Exception {

        final String result = BuildHelper.recapitalizeRemovingUnderscores("regexLike");
        assert result.equals("regexLike");

    }

    @Test
    public void testReapitalyzeWithoutUnderscoreCapitalLetters() throws Exception {

        final String result = BuildHelper.recapitalizeRemovingUnderscores("EEOInfoRequestDate");
        assert result.equals("EEOInfoRequestDate");

    }

    @Test
    public void testExtractGenericType() {
        assertEquals("my.class.Name", BuildHelper.extractGenericTypeFromCollection("java.util.List<my.class.Name>"));
        assertEquals("com.pi.Requisition", BuildHelper.extractGenericTypeFromCollection("java.util.List<com.pi.Requisition>"));
        assertEquals("java.lang.Object", BuildHelper.extractGenericTypeFromCollection("java.util.List"));
    }

    private File getSourceFile(String strFileName) {
        ClassLoader classLoader = getClass().getClassLoader();
        return new File(classLoader.getResource(strFileName).getFile());
    }


}
