package net.pibenchmark;

import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.*;
import com.thoughtworks.qdox.model.expression.AnnotationValue;
import com.thoughtworks.qdox.model.expression.AnnotationValueList;
import com.thoughtworks.qdox.model.impl.DefaultJavaAnnotation;
import net.pibenchmark.pojo.FieldType;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElements;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds one JPA file based on a given interfaces.
 */
public class BuildHelper {

    private static final int GETTER_PREFIX_LENGTH = "get".length();

    private static final Set<String> PRIMITIVES = ImmutableSet.of(
            String.class.getTypeName(),
            int.class.getTypeName(),
            Integer.class.getTypeName(),
            double.class.getTypeName(),
            Double.class.getTypeName(),
            Boolean.class.getTypeName(),
            boolean.class.getTypeName(),
            Byte.class.getTypeName(),
            byte.class.getTypeName(),
            Float.class.getTypeName(),
            float.class.getTypeName(),
            Short.class.getTypeName(),
            short.class.getTypeName(),
            java.math.BigInteger.class.getTypeName());

    public static final Set<String> RESERVED_TYPES = ImmutableSet.of(
            "org.apache.xmlbeans.XmlObject"
    );

    private static final String GENERIC_TYPE_REGEXP = "(?<=\\<)[\\w\\.]+(?=>)";

    /**
     * Builds map "field" <==> "type". We take fields only by getters,
     * because XMLBeans generates other methods, for example xset...().
     * Despite the fact that CXF generates classes with fields and getters,
     * we can not rely on the fields, because a Axis generates only interfaces.
     *
     * @param jc
     * @param mapInterfaces
     * @param log
     * @return
     */
    public static Map<String, FieldType> buildMapOfFields(JavaClass jc, Map<String, String> mapInterfaces,
                                                          JavaClass mostUpperClass, Log log, String idFieldName,
                                                          String idFieldType, JavaProjectBuilder builder) {

        Map<String, FieldType> map = Maps.newTreeMap();
        boolean isGetter;

        final List<JavaMethod> lstMethods = jc.getMethods();
        /* if (null != jc.getSuperJavaClass()) {
            lstMethods.addAll(jc.getSuperJavaClass().getMethods());
        } */

        for (JavaMethod method : lstMethods) {
            isGetter = (method.getName().startsWith("get") && method.getParameters().isEmpty());
            if(isGetter) {
                final FieldType returnType = getReturnType(mostUpperClass, method, mapInterfaces, idFieldName, log, builder);

                if (!RESERVED_TYPES.contains(returnType.getTypeName()) && returnType.isDefined()) {
                    String fieldName = extractFieldName(method.getName());
                    final String fieldByNameClone = fieldName; // in order to use it in lambdas

                    // check: if a field exists, then it must be annotated with @XmlElement
                    final Optional<JavaField> optField = collectParentFields(jc)
                            .parallelStream()
                            .filter((javaField) -> javaField.getName().equalsIgnoreCase(fieldByNameClone))
                            .findFirst();

                    if (optField.isPresent()) {

                        // if current field is marked as @XmlElement, then use value from this annotation. Otherwise, use field name iself
                        fieldName = optField.get().getAnnotations()
                                .stream()
                                .filter((annotation) -> annotation.getType().isA(XmlElement.class.getCanonicalName())
                                        && annotation.getProperty("name") != null)
                                .map((annotation) -> annotation.getProperty("name").toString().replaceAll("\"", ""))
                                .map(BuildHelper::recapitalizeRemovingUnderscores) // <-- workaround
                                .findFirst()
                                .orElse(fieldName);

                        // if current field is polymorphic (has many implementations) then collect all its implementations
                        returnType.addImplementations(extractImplementations(optField.get()));

                    }

                    if (fieldName.equalsIgnoreCase(idFieldName)) {
                        final boolean isCastingNeeded = !returnType.getOriginalTypeName().equalsIgnoreCase(idFieldType);
                        if (isCastingNeeded) {
                            returnType.setShouldBeCasted(isCastingNeeded);
                            returnType.cast(returnType.getOriginalTypeName(), idFieldType);
                        }
                    }
                    map.put(fieldName, returnType);

                }
            }
        }
        return map;
    }

    /**
     * Workaround: replace such field names like "regex_like" back to camel-case: "regexLike"
     *
     * @param val
     * @return
     */
    static String recapitalizeRemovingUnderscores(String val) {
        String[] output = val.split("_");
        if (output.length > 1) {
            for (int j = 1; j < output.length; j++) {
                output[j] = StringUtils.capitalize(output[j]);
            }
        }
        return StringUtils.join(output,"");
    }

    static boolean recursivelyLookupForIDfield(JavaClass javaClass, String idFieldName) {
        if (null == javaClass || javaClass.isArray() || javaClass.isPrimitive() || javaClass.getFullyQualifiedName().equals(java.lang.Object.class.getTypeName())) {
            return false;
        }
        else if (null == javaClass.getSuperJavaClass()) {
            return false;
        }
        else if(null == javaClass.getFieldByName(idFieldName)) {
            return recursivelyLookupForIDfield(javaClass.getSuperJavaClass(), idFieldName);
        }
        else {
            return true;
        }
    }

    static List<JavaField> collectParentFields(JavaClass javaClass){
        if (null == javaClass || javaClass.isArray() || javaClass.isPrimitive()) {
            return Lists.newArrayList();
        }
        else if (null == javaClass.getSuperJavaClass()) {
            return javaClass.getFields();
        }
        else {
            final List<JavaField> fields = javaClass.getFields();
            fields.addAll(javaClass.getSuperJavaClass().getFields());
            return fields;
        }
    }


    /**
     * Returns correct type for a method. In the stub a method returns XMLbean name, but we want
     * that this class should be assigned to according JPA class.
     *
     * @param method
     * @param mapInterfaces
     * @param log
     * @return type as string
     */
    static FieldType getReturnType(JavaClass jc, JavaMethod method, Map<String, String> mapInterfaces,
                                   String idFieldName, Log log, JavaProjectBuilder builder) {


        final String strType = method.getReturnType().getGenericFullyQualifiedName().replace('$', '.');
        final String strSimpleType = method.getReturns().getName();

        if (PRIMITIVES.contains(strType)) {
            return new FieldType(FieldType.PRIMITIVE, strType, strType, strSimpleType, false, 0, false);
        }
        else if (strType.startsWith(List.class.getTypeName()) || strType.startsWith(Set.class.getTypeName())) {
            final boolean isGenericInner = method.getReturnType().getGenericFullyQualifiedName().contains("$");
            final String genericType = extractGenericTypeFromCollection(strType);
            final String originalSimpleName = genericType.substring(genericType.lastIndexOf('.') + 1);

            // get generic class as JavaClass from builder and count fields number
            final JavaClass genericClass = builder.getClassByName(genericType);
            final int genericClassFieldsCount = genericClass.getFields().size();

            final boolean hasIdentField = recursivelyLookupForIDfield(genericClass, idFieldName);
            final boolean isJpa = mapInterfaces.containsKey(genericType); // is it JPA or any other class

            final FieldType fieldType = new FieldType(FieldType.COLLECTION,
                    isJpa ? mapInterfaces.get(genericType) : genericType,
                    genericType,
                    originalSimpleName,
                    hasIdentField,
                    genericClassFieldsCount,
                    isJpa);
            fieldType.setGenericInnerClass(isGenericInner);
            return fieldType;

        }
        else if (strType.endsWith("[]")) {
            final String strTypeOfArray = strType.substring(0, strType.length()-2);
            final boolean isArrayTypeIsSubclass = strTypeOfArray.contains(jc.getCanonicalName());

            if (PRIMITIVES.contains(strTypeOfArray)) {
                // array of primitives
                return new FieldType(FieldType.ARRAY_OF_PRIMITIVES, strTypeOfArray, strTypeOfArray, strTypeOfArray, false, 0, false);
            }
            else if (isArrayTypeIsSubclass) {
                // array of inner class objects
                return new FieldType(FieldType.ARRAY_OF_INNER_CLASSES,
                        method.getReturns().getName() + "JPA",
                        strTypeOfArray,
                        strTypeOfArray,
                        false,
                        0,
                        false);
            }
            else {
                // array of complex types
                return new FieldType(FieldType.ARRAY_OF_COMPLEX_TYPES,
                        mapInterfaces.get(strTypeOfArray),
                        strTypeOfArray,
                        strTypeOfArray,
                        false,
                        0,
                        mapInterfaces.containsKey(strTypeOfArray));
            }
        }
        else {

            final boolean hasIdentField = recursivelyLookupForIDfield(method.getReturns(), idFieldName);
            final int fieldsCount = method.getReturns().getFields().size();
            final boolean isTypeInnerClass = method.getReturns().isInner();

            if (isTypeInnerClass) {
                if (!mapInterfaces.containsKey(strType)) {
                    log.warn("Can not transfer type " + strType + " to JPA inner class. " +
                            "Probably, this is a class instead of an interface. This field will be omitted.");
                }
                return new FieldType(FieldType.INNER_CLASS, mapInterfaces.get(strType), strType, strSimpleType, hasIdentField, fieldsCount, true);
            }
            else {
                return new FieldType(FieldType.COMPLEX_TYPE, mapInterfaces.get(strType), strType, strSimpleType, hasIdentField, fieldsCount, true);
            }
        }
    }

    /**
     * Extracts the generic class for any type having generics. Speaking in other words,
     * it extracts type between "<" and ">". If not found, returns Object.class.
     *
     * @param collectionType
     * @return generic type
     */
    static String extractGenericTypeFromCollection(String collectionType) {
        final Matcher matcher = Pattern.compile(GENERIC_TYPE_REGEXP).matcher(collectionType);
        return matcher.find() ? matcher.group() : Object.class.getTypeName();
    }

    /**
     * Write down all the content to a given file
     *
     * @param content
     * @param file
     * @return result as boolean
     *
     * @throws MojoFailureException
     */
    public static boolean writeContentToFile(String content, File file) throws MojoFailureException {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(file))) {

            bw.write(content.toString());

        } catch (IOException e) {
            throw new MojoFailureException(e.getMessage());
        }

        return true;
    }

    /**
     * Extracts the name for a field using getter name
     * Example, for the getter "getSecondName" it returns "secondName"
     *
     * @param strGetterName
     * @return
     */
    static String extractFieldName(String strGetterName) {
        return strGetterName.substring(GETTER_PREFIX_LENGTH, GETTER_PREFIX_LENGTH + 1).toLowerCase() +
                strGetterName.substring(GETTER_PREFIX_LENGTH + 1);

    }

    /**
     * Returns the current package name. In case of inner class, we want to put them to
     * the subpackage "inners".
     *
     * @param jc
     * @return
     */
    public static String getQualifiedName(JavaClass jc) {
        return jc.getFullyQualifiedName();
    }

    /**
     * Creates output directory for generated JPA stubs
     *
     * @return path
     */
    public static File ensureOutputDirExists(String targetAbsPath) {
        final String strJPAOutputDir = new StringBuilder(targetAbsPath)
                .append(File.separator)
                .append("generated-sources")
                .append(File.separator)
                .append(Constants.STR_PLUGIN_NAME)
                .append(File.separator)
                .append("src")
                .toString();

        final File jpaOutputDir = new File(strJPAOutputDir);
        if (!jpaOutputDir.exists()) jpaOutputDir.mkdirs();
        return jpaOutputDir;
    }

    /**
     * Make sure that package directory exists. If not - create new one
     *
     * @param packageName
     */
    public static String ensurePackageExists(final String jpaOutputDirectory, final String packageName) {

        final String absPackagePath = new StringBuilder()
                .append(jpaOutputDirectory)
                .append(File.separator)
                .append(packageName.replace(".", File.separator))
                .toString();

        File newPackage = new File(absPackagePath);
        if (!newPackage.exists()) newPackage.mkdirs();
        return absPackagePath;
    }

    /**
     * Create new JPA file, if it does not exist yet
     *
     * @param strPackagePath
     * @param fileName
     *
     * @throws IOException
     */
    public static File getFile(final String strPackagePath, final String fileName, final String suffix) throws IOException {

        final String absPathJpaFile = new StringBuilder()
                .append(strPackagePath)
                .append(File.separator)
                .append(fileName)
                .append(suffix)
                .append(".java")
                .toString();

        return new File(absPathJpaFile);
    }

    /**
     * Create new JPA file, if it does not exist yet
     *
     * @throws IOException
     */
    public static File ensureFactoryFileExists(final String strPackagePath, final int chunkNumber) throws IOException {

        final String absPathFactoryFile = new StringBuilder()
                .append(strPackagePath)
                .append(File.separator)
                .append(chunkNumber == 0 ? "JPAEntitiesFactory.java" : "JPAEntitiesFactoryChunk" + chunkNumber + ".java")
                .toString();

        File factoryFile = new File(absPathFactoryFile);
        if (!factoryFile.exists()) factoryFile.createNewFile();
        return factoryFile;
    }

    /**
     * <p>If a field is marked as @XmlElements, then it should contain the list of implementations.</p>
     *
     * <p>For example, </br>
     *
     *   <pre>
     *         @XmlElements({
     *            @XmlElement(name = "FirstClass"),
     *            @XmlElement(name = "SecondClass", type = SecondClass.class),
     *            @XmlElement(name = "ThirdClass", type = ThirdClass.class),
     *            @XmlElement(name = "FourthClass", type = FourthClass.class),
     *            @XmlElement(name = "FifthClass", type = FifthClass.class),
     *        })
     *       @Generated(value = "com.sun.tools.xjc.Driver", date = "2015-02-20T03:52:29+00:00", comments = "JAXB RI v2.2.10-b140310.1920")
     *       protected List<FirstClass> lstField;
     * </pre>
     *
     * As you can see, that given field has several implementations:
     * SecondClass, ThirdClass, FourthClass and FifthClass. This method allows
     * to extract the list containing these classes
     * </p>
     *
     * <p>Note, that the very first class ("FirstClass" in this case) should be ommitted,
     * because it is generic class and doesn't contain "type" attribute.</p>
     *
     * <p>Please refer to unit tests</p>
     * @param field
     * @return
     */
    public static Set<String> extractImplementations(JavaField field) {

        // search for @XmlElements annotation
        final Optional<JavaAnnotation> optElementsAnnotation = field.getAnnotations()
                .stream()
                .filter((annotation) -> annotation.getType().isA(XmlElements.class.getCanonicalName()))
                .findFirst();

        if (optElementsAnnotation.isPresent()) {
            // get list of all the inner @XmlElement elements
            final AnnotationValue value = optElementsAnnotation.get().getProperty("value");
            final List<AnnotationValue> annotationValueList = ((AnnotationValueList) value).getValueList();

            // ...and collect values from these annotations
            return annotationValueList
                    .stream()
                    .map((val) -> (DefaultJavaAnnotation) val.getParameterValue())
                    .filter((ann) -> ann.getPropertyMap().containsKey("type"))
                    .map( (ann) -> ann.getProperty("name").getParameterValue().toString().replaceAll("\"", ""))
                    .collect(Collectors.toSet());
        }
        else {
            return ImmutableSet.of();
        }
    }
}