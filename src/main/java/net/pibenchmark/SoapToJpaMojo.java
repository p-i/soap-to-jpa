package net.pibenchmark;

import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.JavaClass;
import net.pibenchmark.pojo.FieldType;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.apache.velocity.tools.generic.DisplayTool;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Map;
import java.util.stream.Collectors;

@Mojo( name = "soap-to-jpa")
public class SoapToJpaMojo extends AbstractMojo {

    public static final String STR_PLUGIN_NAME = "soapToJpa";

    /**
     * Reference to a project, where the current plugin is used
     */
    @Parameter( defaultValue = "${project}", readonly = true )
    private MavenProject project;

    /**
     * BaseDir of a project, where from this plugin is called
     */
    @Parameter( defaultValue = "${project.basedir}", readonly = true )
    private File basedir;

    @Parameter( defaultValue = "${settings}", readonly = true )
    private Settings settings;

    @Parameter( defaultValue = "${project.build.directory}", readonly = true )
    private File target;

    @Parameter( defaultValue = "org.apache.maven.soap.jpa.factory", readonly = true )
    private String factoryPackageName;

    private JavaProjectBuilder builder;
    private File jpaOutputDirectory;

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        this.builder = new JavaProjectBuilder();

        this.jpaOutputDirectory = ensureOutputDirExists();
        builder.addSourceFolder(this.jpaOutputDirectory);

        builder.addSourceTree(new File(target.getAbsolutePath() + "/generated-sources/axis2/wsdl2code/src"));

        VelocityEngine ve = new VelocityEngine();
        ve.setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath");
        ve.setProperty("classpath.resource.loader.class", ClasspathResourceLoader.class.getName());
        ve.init();

        Template jpaTemplate = ve.getTemplate("JpaEntityTemplate.vm");
        Template factoryTemplate = ve.getTemplate("FactoryTemplate.vm");

        try {

            // collect all interfaces to map "full interface name" <==> "fully qualified JPA name"
            Map<String, String> mapInterfaces = builder.getClasses()
                    .stream()
                    .collect(Collectors.toMap(
                            JavaClass::getCanonicalName,
                            jc -> getQualifiedName(jc) + "JPA",
                            (a, b) -> a));

            // write all the JPA classes
            this.generateJpaClasses(jpaTemplate, mapInterfaces);

            // write the Factory
            this.generateFactoryClass(factoryTemplate, mapInterfaces);

        }
        catch (Exception e) {
            e.printStackTrace();
            throw new MojoFailureException(e.getMessage());
        }

    }

    /**
     * Generates one file that can produce an instance of JPA regarding a Soap Stub - factory.
     *
     * @param factoryTemplate
     * @param mapInterfaces
     */
    private void generateFactoryClass(Template factoryTemplate, Map<String, String> mapInterfaces) throws IOException, MojoFailureException {
        final String packagePath = ensurePackageExists(factoryPackageName);
        File factoryFile = this.ensureFactoryFileExists(packagePath);

        VelocityContext context = new VelocityContext();
        context.put("package", factoryPackageName);
        context.put("interfaces", mapInterfaces);

        StringWriter writer = new StringWriter();
        factoryTemplate.merge( context, writer );

        BuildJPAFileContent.writeContentToFile(writer.toString(), factoryFile);
    }

    /**
     * Generate JPA class for each interface, found in the generate-sources directory. Subclasses will
     * be stored in a separate subpaclage "inners"
     *
     * @param t - Velocity template
     * @param mapInterfaces - map "interface class" <==> "JPA class"
     *
     * @throws IOException
     * @throws MojoFailureException
     */
    private void generateJpaClasses(Template t, Map<String, String> mapInterfaces) throws IOException, MojoFailureException {
        for (JavaClass jc : builder.getClasses()) {
            if (jc.isInterface()) {

                final String packageName = jc.isInner() ? jc.getPackageName() + ".inners" : jc.getPackageName();
                final String packagePath = ensurePackageExists(packageName);
                File jpaFile = getJpaFile(packagePath, jc);

                if (!jpaFile.exists()) {
                    jpaFile.createNewFile();

                    Map<String, FieldType> mapOfFields = BuildJPAFileContent.buildMapOfFields(jc, mapInterfaces);

                    VelocityContext context = new VelocityContext();
                    context.put("package", packageName);
                    context.put("className", jc.getName());
                    context.put("fieldMap", mapOfFields);
                    context.put("interfaceType", jc.getFullyQualifiedName().replace('$','.'));
                    context.put("display", new DisplayTool());

                    StringWriter writer = new StringWriter();
                    t.merge( context, writer );

                    BuildJPAFileContent.writeContentToFile(writer.toString(), jpaFile);
                }
            }
        }
    }

    /**
     * Returns the current package name. In case of inner class, we want to put them to
     * the subpackage "inners".
     *
     * @param jc
     * @return
     */
    private String getQualifiedName(JavaClass jc) {
        return jc.isInner() ? jc.getPackageName() + ".inners." + jc.getName() : jc.getFullyQualifiedName();
    }

    /**
     * Creates output directory for generated JPA stubs
     *
     * @return path
     */
    private File ensureOutputDirExists() {
        final String strJPAoutputDir = new StringBuilder(this.target.getAbsolutePath())
                .append(File.separator)
                .append("generated-sources")
                .append(File.separator)
                .append(STR_PLUGIN_NAME)
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
    private String ensurePackageExists(String packageName) {

        final String absPackagePath = new StringBuilder()
                .append(this.jpaOutputDirectory.getAbsolutePath())
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
    private File getJpaFile(final String strPackagePath, JavaClass jc) throws IOException {

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
    private File ensureFactoryFileExists(final String strPackagePath) throws IOException {

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