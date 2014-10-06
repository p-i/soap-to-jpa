package net.pibenchmark;

import com.google.common.collect.ImmutableSet;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaMethod;
import org.apache.maven.plugin.MojoFailureException;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Builds one JPA file based on a given interfaces.
 *
 * Debug this plugin:
 *    mvn net.pibenchmark:soap-to-jpa-maven-plugin:1.0-SNAPSHOT:soap-to-jpa
 */
public class BuildJPAFileContent {

    private static final int GETTER_PREFIX_LENGTH = "get".length();
    private static final Set<String> PRIMITIVES = ImmutableSet.of(
            String.class.getTypeName(),
            int.class.getTypeName());

    /**
     * Builds map "field" <==> "type". We take fields only by getters,
     * because XMLBeans generates other methods, for example xset...()
     *
     * @param jc
     * @param mapInterfaces
     * @return
     */
    public static Map<String, String> buildMapOfFields(JavaClass jc, Map<String, String> mapInterfaces) {
        Map<String, String> mapField = new HashMap<>();

        for (JavaMethod method : jc.getMethods()) {
            if(method.getName().startsWith("get")) {
                mapField.put(extractFieldName(method.getName()), getReturnType(method, mapInterfaces));
            }
        }

        return mapField;
    }

    /**
     * Returns correct type for a method. In the stub a method returns XMLbean name, but we want
     * that this class should be assigned to according JPA class.
     *
     * @param method
     * @param mapInterfaces
     * @return type as string
     */
    private static String getReturnType(JavaMethod method, Map<String, String> mapInterfaces) {
        final String strType = method.getReturnType().getCanonicalName();

        if (PRIMITIVES.contains(strType)) {
            return method.getReturns().getName();
        }
        else {
            return mapInterfaces.getOrDefault(strType, strType);
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
}