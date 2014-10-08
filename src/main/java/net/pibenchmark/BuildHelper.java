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
            boolean.class.getTypeName());

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
        for (JavaMethod method : jc.getMethods()) {
            if(method.getName().startsWith("get")) {
                map.put(extractFieldName(method.getName()), getReturnType(method, mapInterfaces));
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
    private static FieldType getReturnType(JavaMethod method, Map<String, String> mapInterfaces) {
        final String strType = method.getReturnType().getCanonicalName();

        if (PRIMITIVES.contains(strType)) {
            return new FieldType(true, method.getReturns().getName()) ;
        }
        else {
            return new FieldType(false, mapInterfaces.getOrDefault(strType, strType));
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
        return jc.isInner() ? jc.getPackageName() + ".inners." + jc.getName() : jc.getFullyQualifiedName();
    }

    /**
     * Creates output directory for generated JPA stubs
     *
     * @return path
     */
    public static File ensureOutputDirExists(String targetAbsPath) {
        final String strJPAoutputDir = new StringBuilder(targetAbsPath)
                .append(File.separator)
                .append("generated-sources")
                .append(File.separator)
                .append(Constants.STR_PLUGIN_NAME)
                .append(File.separator)
                .append("src")
                .toString();

        final File jpaOutputDir = new File(strJPAoutputDir);
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
     * @param jc
     * @throws IOException
     */
    public static File getJpaFile(final String strPackagePath, JavaClass jc) throws IOException {

        final String absPathJpaFile = new StringBuilder()
                .append(strPackagePath)
                .append(File.separator)
                .append(jc.getName())
                .append("JPA.java")
                .toString();

        return new File(absPathJpaFile);
    }

    /**
     * Create new JPA file, if it does not exist yet
     *
     * @throws IOException
     */
    public static File ensureFactoryFileExists(final String strPackagePath) throws IOException {

        final String absPathFactoryFile = new StringBuilder()
                .append(strPackagePath)
                .append(File.separator)
                .append("JPAEntitiesFactory.java")
                .toString();

        File factoryFile = new File(absPathFactoryFile);
        if (!factoryFile.exists()) factoryFile.createNewFile();
        return factoryFile;
    }
}