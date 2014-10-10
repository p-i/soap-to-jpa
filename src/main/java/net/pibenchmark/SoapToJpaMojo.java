package net.pibenchmark;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
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
import java.util.Set;
import java.util.stream.Collectors;

@Mojo( name = "soap-to-jpa")
public class SoapToJpaMojo extends AbstractMojo {

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

    // where the generated SOAP stubs can be found
    @Parameter( defaultValue = "${project.build.directory}/generated-sources/axis2/wsdl2code/src", readonly = true )
    private File generatedSoapStubsDir;

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

        this.setUpPlugin();

        VelocityEngine ve = this.setUpVelocity();

        Template jpaTemplate = ve.getTemplate("JpaEntityTemplate.vm");
        Template factoryTemplate = ve.getTemplate("FactoryTemplate.vm");

        try {

            // collect all interfaces to map "full interface name" <==> "fully qualified JPA name"
            Map<String, String> mapInterfaces = builder.getClasses()
                    .stream()
                    .collect(Collectors.toMap(
                            JavaClass::getCanonicalName,
                            jc -> BuildHelper.getQualifiedName(jc) + "JPA",
                            (a, b) -> a));

            // map "JPA class name" <==> "Set<full interface name>"
            Map<String, Set<String>> mapOfConstructors = this.buildMapOfConstructors(mapInterfaces);

            // write all the JPA classes
            this.generateJpaClasses(jpaTemplate, mapInterfaces, mapOfConstructors);

            // write the Factory class
            this.generateFactoryClass(factoryTemplate, mapInterfaces);

        }
        catch (Exception e) {
            throw new MojoFailureException(e.getMessage());
        }

    }

    /**
     * Perform some initial stuff for the plugin
     */
    private void setUpPlugin() {
        this.builder = new JavaProjectBuilder();
        this.jpaOutputDirectory = BuildHelper.ensureOutputDirExists(this.target.getAbsolutePath());
        builder.addSourceFolder(this.jpaOutputDirectory);
        builder.addSourceTree(this.generatedSoapStubsDir);

        getLog().info("Directory for generated JPA files: " + this.jpaOutputDirectory.getAbsolutePath());
        getLog().info("Generated SOAP files will be searched from the directory: " + this.generatedSoapStubsDir.getAbsolutePath());
        getLog().info("Factory will be placed to the package: " + this.factoryPackageName);
    }

    /**
     * Set up Apache Velocity template engine
     * @return
     */
    private VelocityEngine setUpVelocity() {
        VelocityEngine ve = new VelocityEngine();
        ve.setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath");
        ve.setProperty("classpath.resource.loader.class", ClasspathResourceLoader.class.getName());
        ve.init();
        return ve;
    }

    /**
     * Fold all the JPA classes into a set, that are referenced to a same interfaces
     *
     * @param mapInterfaces
     * @return
     */
    private Map<String, Set<String>> buildMapOfConstructors(Map<String, String> mapInterfaces) {
        Map<String, Set<String>> mapOfConstructors = Maps.newHashMap();
        mapInterfaces.forEach( (interfaceName, JpaClassName) -> {
            if (mapOfConstructors.containsKey(JpaClassName)) {
                mapOfConstructors.get(JpaClassName).add(interfaceName);
            }
            else {
                mapOfConstructors.put(JpaClassName, Sets.newHashSet(interfaceName));
            }
        });
        return mapOfConstructors;
    }

    /**
     * Generates one file that can produce an instance of JPA regarding a Soap Stub - factory.
     *
     * @param factoryTemplate
     * @param mapInterfaces
     */
    private void generateFactoryClass(Template factoryTemplate, Map<String, String> mapInterfaces) throws IOException, MojoFailureException {
        final String packagePath = BuildHelper.ensurePackageExists(this.jpaOutputDirectory.getAbsolutePath(), factoryPackageName);
        File factoryFile = BuildHelper.ensureFactoryFileExists(packagePath);

        VelocityContext context = new VelocityContext();
        context.put("package", factoryPackageName);
        context.put("interfaces", mapInterfaces);

        StringWriter writer = new StringWriter();
        factoryTemplate.merge( context, writer );

        BuildHelper.writeContentToFile(writer.toString(), factoryFile);
    }

    /**
     * Generate JPA class for each interface, found in the generate-sources directory. Subclasses will
     * be stored in a separate subpaclage "inners"
     *
     * @param t - Velocity template
     * @param mapInterfaces - map "interface class" <==> "JPA class"
     *
     * @param mapOfConstructors
     * @throws IOException
     * @throws MojoFailureException
     */
    private void generateJpaClasses(Template t, Map<String, String> mapInterfaces, Map<String, Set<String>> mapOfConstructors) throws IOException, MojoFailureException {
        getLog().info("Generation of the JPA objects...");
        int cntCreatedFiles = 0;
        int cntSkippedFiles = 0;

        for (JavaClass jc : builder.getClasses()) {
            if (jc.isInterface()) {

                final String packageName = jc.isInner() ? jc.getPackageName() + ".inners" : jc.getPackageName();
                final String packagePath = BuildHelper.ensurePackageExists(this.jpaOutputDirectory.getAbsolutePath(), packageName);
                final String className = packageName + "." + jc.getName() + "JPA";
                File jpaFile = BuildHelper.getJpaFile(packagePath, jc);

                if (!jpaFile.exists()) {
                    jpaFile.createNewFile();

                    Map<String, FieldType> mapOfFields = BuildHelper.buildMapOfFields(jc, mapInterfaces);

                    VelocityContext context = new VelocityContext();
                    context.put("package", packageName);
                    context.put("className", jc.getName());
                    context.put("fieldMap", mapOfFields);
                    context.put("constructors", mapOfConstructors.get(className));
                    context.put("interfaceType", jc.getFullyQualifiedName().replace('$','.'));
                    context.put("display", new DisplayTool());

                    StringWriter writer = new StringWriter();
                    t.merge( context, writer );

                    BuildHelper.writeContentToFile(writer.toString(), jpaFile);

                    cntCreatedFiles++;
                }
                else {
                    cntSkippedFiles++;
                }
            }
        }
        getLog().info(cntCreatedFiles + " files were generated and " + cntSkippedFiles + " were skipped");
    }


}