package net.pibenchmark;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaField;
import com.thoughtworks.qdox.model.JavaMethod;
import net.pibenchmark.pojo.FieldType;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;

import javax.xml.bind.annotation.XmlElement;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds one JPA file based on a given interfaces.
 */
public class BuildHelper {

    private static final int GETTER_PREFIX_LENGTH = "get".length();
    private static final String OBJECT_CLASS_NAME = Object.class.getTypeName();

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
                                                          JavaClass mostUpperClass, Log log, String idFieldName, String idFieldType) {

        Map<String, FieldType> map = Maps.newTreeMap();
        boolean isGetter;

        final List<JavaMethod> lstMethods = jc.getMethods();
        if (null != jc.getSuperJavaClass()) {
            lstMethods.addAll(jc.getSuperJavaClass().getMethods());
        }

        for (JavaMethod method : lstMethods) {
            isGetter = (method.getName().startsWith("get") && method.getParameters().isEmpty());
            if(isGetter) {
                final FieldType returnType = getReturnType(mostUpperClass, method, mapInterfaces, idFieldName, log);
                if (!RESERVED_TYPES.contains(returnType.getTypeName()) && returnType.isDefined()) {
                    final String fieldName = extractFieldName(method.getName());

                    // check: if a field exists, then it must be annotated with @XmlElement
                    boolean shouldBeSkipped = false;
                    final JavaField fieldByName = jc.getFieldByName(fieldName);
                    if (null != fieldByName) {
                        shouldBeSkipped = fieldByName.getAnnotations()
                                .stream()
                                .noneMatch((annotation) -> annotation.getType().isA(XmlElement.class.getCanonicalName()));
                    }

                    if (fieldName.equals(idFieldName)) {
                        final boolean isCastingNeeded = !returnType.getOriginalTypeName().equals(idFieldType);
                        if (isCastingNeeded) {
                            returnType.setShouldBeCasted(isCastingNeeded);
                            returnType.cast(returnType.getOriginalTypeName(), idFieldType);
                        }
                    }
                    if (!shouldBeSkipped) map.put(fieldName, returnType);

                }
            }
        }
        return map;
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
    static FieldType getReturnType(JavaClass jc, JavaMethod method, Map<String, String> mapInterfaces, String idFieldName, Log log) {
        final String strType = method.getReturnType().getGenericFullyQualifiedName().replace('$', '.');
        final String strSimpleType = method.getReturns().getName();

        final boolean isTypeInnerClass = strType.length() > jc.getCanonicalName().length()
                                          && strType.startsWith(jc.getCanonicalName() + ".");

        final boolean hasIdentField = collectParentFields(method.getReturns())
                .parallelStream()
                .anyMatch((JavaField field) -> field.getName().equalsIgnoreCase(idFieldName));

        if (PRIMITIVES.contains(strType)) {
            return new FieldType(FieldType.PRIMITIVE, strType, strType, strSimpleType, hasIdentField);
        }
        else if (strType.equals(List.class.getTypeName()) || strType.equals(Set.class.getTypeName())) {
            return new FieldType(FieldType.COLLECTION, method.getReturns().getName() + "JPA", strType, strSimpleType, hasIdentField);
        }
        else if (strType.endsWith("[]")) {
            final String strTypeOfArray = strType.substring(0, strType.length()-2);
            final boolean isArrayTypeIsSubclass = strTypeOfArray.contains(jc.getCanonicalName());

            if (PRIMITIVES.contains(strTypeOfArray)) {
                // array of primitives
                return new FieldType(FieldType.ARRAY_OF_PRIMITIVES, strTypeOfArray, strTypeOfArray, strTypeOfArray, hasIdentField);
            }
            else if (isArrayTypeIsSubclass) {
                // array of inner class objects
                return new FieldType(FieldType.ARRAY_OF_INNER_CLASSES,
                        method.getReturns().getName() + "JPA",
                        strTypeOfArray,
                        strTypeOfArray,
                        hasIdentField);
            }
            else {
                // array of complex types
                return new FieldType(FieldType.ARRAY_OF_COMPLEX_TYPES,
                        mapInterfaces.get(strTypeOfArray),
                        strTypeOfArray,
                        strTypeOfArray,
                        hasIdentField);
            }
        }
        else if (isTypeInnerClass) {
            if (!mapInterfaces.containsKey(strType)) {
                log.warn("Can not transfer type " + strType + " to JPA inner class. " +
                        "Probably, this is a class instead of an interface. This field will be omitted.");
            }
            return new FieldType(FieldType.INNER_CLASS, mapInterfaces.get(strType), strType, strSimpleType, hasIdentField);
        }
        else {
            return new FieldType(FieldType.COMPLEX_TYPE, mapInterfaces.get(strType), strType, strSimpleType, hasIdentField);
        }
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
}