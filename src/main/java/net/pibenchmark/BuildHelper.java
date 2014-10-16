package net.pibenchmark;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaMethod;
import net.pibenchmark.pojo.FieldType;
import org.apache.maven.plugin.MojoFailureException;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds one JPA file based on a given interfaces.
 *
 * Debug this plugin:
 *    mvn net.pibenchmark:soap-to-jpa-maven-plugin:1.0-SNAPSHOT:soap-to-jpa
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

    /**
     * Builds map "field" <==> "type". We take fields only by getters,
     * because XMLBeans generates other methods, for example xset...()
     *
     * @param jc
     * @param mapInterfaces
     * @return
     */
    public static Map<String, FieldType> buildMapOfFields(JavaClass jc, Map<String, String> mapInterfaces) {
        Map<String, FieldType> map = Maps.newHashMap();
        boolean isGetter;
        for (JavaMethod method : jc.getMethods()) {
            isGetter = (method.getName().startsWith("get") && method.getParameters().isEmpty());
            if(isGetter) {
                final FieldType returnType = getReturnType(method, mapInterfaces);
                if (!RESERVED_TYPES.contains(returnType.getTypeName()) && returnType.isDefined()) {
                    map.put(extractFieldName(method.getName()), returnType);
                }
            }
        }
        return map;
    }

    /**
     * Returns correct type for a method. In the stub a method returns XMLbean name, but we want
     * that this class should be assigned to according JPA class.
     *
     * @param method
     * @param mapInterfaces
     * @return type as string
     */
    static FieldType getReturnType(JavaMethod method, Map<String, String> mapInterfaces) {
        final String strType = method.getReturnType().getGenericFullyQualifiedName().replace('$', '.');

        if (PRIMITIVES.contains(strType)) {
            return new FieldType(FieldType.PRIMITIVE, strType, strType) ;
        }
        else if (strType.equals(List.class.getTypeName()) || strType.equals(Set.class.getTypeName())) {
            return new FieldType(FieldType.COLLECTION, method.getReturns().getName(), strType) ;
        }
        else if (strType.endsWith("[]")) {
            final String strTypeOfArray = strType.substring(0, strType.length()-2);
            if (PRIMITIVES.contains(strTypeOfArray)) {
                // array of primitives
                return new FieldType(FieldType.ARRAY_OF_PRIMITIVES, strTypeOfArray, strTypeOfArray) ;
            }
            else {
                // array of complex types
                return new FieldType(FieldType.ARRAY_OF_COMPLEX_TYPES,
                        mapInterfaces.get(strTypeOfArray),
                        strTypeOfArray) ;
            }
        }
        else {
            return new FieldType(FieldType.COMPLEX_TYPE, mapInterfaces.get(strType), strType);
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
     * @return array: [0] - package, [1] - class name
     */
    public static String[] getQualifiedName(JavaClass jc) {
        final String packageName = jc.isInner() ?
                (jc.getPackageName() + ".inners")
                : jc.getPackageName();

        final String className = (jc.isInner() ?
                        jc.getDeclaringClass().getGenericValue().replace('.', '_') + "_" + jc.getName() :
                        jc.getName() ) + "JPA";

        return new String[] {packageName, className};
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
    public static File getJpaFile(final String strPackagePath, final String fileName) throws IOException {

        final String absPathJpaFile = new StringBuilder()
                .append(strPackagePath)
                .append(File.separator)
                .append(fileName)
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