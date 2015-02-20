package net.pibenchmark;


import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.impl.DefaultJavaClass;
import net.pibenchmark.pojo.FieldType;
import org.junit.Test;

import java.io.File;
import java.util.Map;

import static org.junit.Assert.*;

public class SoapToJpaMojoTest{

    SoapToJpaMojo target = new SoapToJpaMojo();

    // base path for "testFiles" directory
    final String baseTestDir = new File("src/test/java").getAbsolutePath()
            + File.separator
            + this.getClass().getPackage().getName().replaceAll("\\.", File.separator)
            + File.separator
            + "testFiles"
            + File.separator;

    @Test
    public void testBuildMapOfInnerClassesBelongingToAnotherEntityRightCase() throws Exception {

        final  String nameOfParentClass = "com.any.package.ParentFile";
        final FieldType children = new FieldType(FieldType.INNER_CLASS, "com.any.package.ParentFile.ChildrenJPA", "com.any.package.ParentFile.Children", "Children", false, 1);

        final Map<String, FieldType> output = target.buildMapOfInnerClassesBelongingToAnotherEntity(nameOfParentClass,
                ImmutableMap.of("children", children));

        assertTrue(output.isEmpty());

    }

    @Test
    public void testBuildMapOfInnerClassesBelongingToAnotherEntityWrongCase() throws Exception {

        final  String nameOfParentClass = "com.any.package.ParentFile";
        final FieldType children = new FieldType(FieldType.INNER_CLASS, "com.any.package.AnotherParentFile.ChildrenJPA", "com.any.package.AnotherParentFile.Children", "Children", false, 1);

        final Map<String, FieldType> output = target.buildMapOfInnerClassesBelongingToAnotherEntity(nameOfParentClass,
                ImmutableMap.of("children", children));

        assertFalse(output.isEmpty());
        assertTrue(output.get("children") == children);
    }


    @Test
    public void testBuildMapOfInnerClassesBelongingToAnotherEntityWrongCaseForEntity() throws Exception {

        final  String nameOfParentClass = "com.any.package.ParentFile";
        final FieldType children = new FieldType(FieldType.INNER_CLASS, "com.any.package.AnotherParentFile.ChildrenJPA", "com.any.package.AnotherParentFile.Children", "Children", true, 1);

        final Map<String, FieldType> output = target.buildMapOfInnerClassesBelongingToAnotherEntity(nameOfParentClass,
                ImmutableMap.of("children", children));

        assertTrue(output.isEmpty());
    }

    @Test
    public void testBuildMapOfInnerClassesBelongingToAnotherEntityFewFieldsWrongCaseForEntity() throws Exception {

        // Given:
        final  String nameOfParentClass = "com.any.package.ParentFile";

        // we have one field belonging to this class
        final FieldType children = new FieldType(FieldType.INNER_CLASS, nameOfParentClass + ".ChildrenJPA", nameOfParentClass + ".Children", "Children", false, 1);
        // and two belonging to another class
        final FieldType organization = new FieldType(FieldType.INNER_CLASS, "com.any.package.AnotherParentFile.OrganizationJPA", "com.any.package.AnotherParentFile.Organization", "Organization", false, 1);
        final FieldType location = new FieldType(FieldType.INNER_CLASS, "com.any.package.AnotherParentFile.LocationJPA", "com.any.package.AnotherParentFile.Location", "Location", false, 1);

        // When we build map of "strangers" fields...
        final Map<String, FieldType> output = target.buildMapOfInnerClassesBelongingToAnotherEntity(nameOfParentClass,
                ImmutableMap.of(
                        "children", children,
                        "organization", organization,
                        "location", location
                        )
                );

        // Then resulting map should contain only strangers
        assertFalse(output.isEmpty());
        assertTrue(output.containsKey("organization"));
        assertTrue(output.get("organization") == organization);
        assertTrue(output.containsKey("location"));
        assertTrue(output.get("location") == location);

        //... and it doesn't contain its own field
        assertFalse(output.containsKey("children"));
    }


    @Test
    public void testChangeClassContext() throws Exception {

        final JavaProjectBuilder builder = new JavaProjectBuilder();
        builder.addSourceTree(new File(baseTestDir));

        final DefaultJavaClass jcOne = (DefaultJavaClass) builder.getClassByName("net.pibenchmark.testFiles.SimpleClassOne");
        final DefaultJavaClass jcTwo = (DefaultJavaClass) builder.getClassByName("net.pibenchmark.testFiles.SimpleClassTwo");

        assertNotNull(jcOne);
        assertNotNull(jcTwo);

        final DefaultJavaClass innerClass = (DefaultJavaClass) jcOne.getInnerClasses().get(0);
        target.changeContextUsing(innerClass, jcTwo);

        assertEquals("SimpleInnerClass",innerClass.getName());
        assertEquals(jcTwo,innerClass.getParentClass());
    }


    @Test
    public void testParentClassReplacer() throws Exception {

        // Given: there are list of all the fields, one is normal, one is stranger (belongs to another class)
        Map<String, FieldType> mapOfFields = ImmutableMap.of(
                "anyField",
                new FieldType(FieldType.INNER_CLASS, "com.any.package.ParentClass.ChildrenJPA", "com.any.package.ParentClass.Children", "Children", false, 1),
                "anyFieldTwo",
                new FieldType(FieldType.INNER_CLASS, "com.any.package.AnotherParentClass.ChildrenTwoJPA", "com.any.package.AnotherParentClass.ChildrenTwo", "ChildrenTwo", false, 1));

        // ... and list of strangers
        Map<String, FieldType> mapOfStrangers = ImmutableMap.of(
                "anyFieldTwo",
                new FieldType(FieldType.INNER_CLASS, "com.any.package.AnotherParentClass.ChildrenTwoJPA", "com.any.package.AnotherParentClass.ChildrenTwo", "ChildrenTwo", false, 1));

        // when we change context for stranger fields
        target.modifyContextForStrangerTypes(mapOfFields, mapOfStrangers, "Test");

        // then:
        assertEquals(mapOfFields.size(), 2);

        // and the first item has remain untouched
        assertEquals("com.any.package.ParentClass.Children", mapOfFields.get("anyField").getOriginalTypeName());

        // and the second one will be changed
        assertEquals("com.any.package.Test.ChildrenTwo", mapOfFields.get("anyFieldTwo").getOriginalTypeName());

    }
}