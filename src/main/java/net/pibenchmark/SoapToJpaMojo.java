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
import java.util.Arrays;
import java.util.Iterator;
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

    // for the compilation performance, one factory can not contain more than this value of iterations
    public static final int MAX_ITERATIONS_PER_FACTORY = 50;

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        this.setUpPlugin();

        VelocityEngine ve = this.setUpVelocity();

        Template jpaTemplate = ve.getTemplate("JpaEntityTemplate.vm");
        Template factoryTemplate = ve.getTemplate("FactoryTemplate.vm");
        Template factoryChunkTemplate = ve.getTemplate("FactoryChunkTemplate.vm");

        try {

            // collect all interfaces to map "full interface name" <==> "fully qualified JPA name"
            Map<String, String> mapInterfaces = builder.getClasses()
                    .stream()
                    .filter( (className) -> !className.toString().endsWith("Factory") && !className.toString().endsWith("Impl") )
                    .filter( (className) -> className.isInterface() )
                    .collect(Collectors.toMap(
                            JavaClass::getCanonicalName,
                            jc -> Arrays.stream(BuildHelper.getQualifiedName(jc)).collect(Collectors.joining(".")),
                            (a, b) -> a));

            // map "JPA class name" <==> "Set<full interface name>"
            Map<String, Set<String>> mapOfConstructors = this.buildMapOfConstructors(mapInterfaces);

            // write all the JPA classes
            this.generateJpaClasses(jpaTemplate, mapInterfaces, mapOfConstructors);

            // write the Factory class
            this.generateFactoryClass(factoryTemplate, factoryChunkTemplate, mapInterfaces);

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
     * Fold all the JPA classes into a set, that are referenced to a same interfaces.
     * Builds map "Jpa class" <==> "Soap interfaces"
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
    private void generateFactoryClass(Template factoryTemplate, Template factoryChunkTemplate, Map<String, String> mapInterfaces) throws IOException, MojoFailureException {
        if (MAX_ITERATIONS_PER_FACTORY < mapInterfaces.size()) {

            getLog().info("Generation of the JPA factory chunks...");
            int chunk = 1;
            do {

                Map<String, String> subMapInterfaces = Maps.newConcurrentMap();

                Iterator<Map.Entry<String, String>> iterator = mapInterfaces.entrySet().iterator();
                for (int i = 0; i < MAX_ITERATIONS_PER_FACTORY; i++) {
                    Map.Entry<String, String> next = iterator.next();
                    subMapInterfaces.put(next.getKey(), next.getValue());
                    iterator.remove();
                }
                _writeFactoryChunkFile(factoryChunkTemplate, subMapInterfaces, chunk++);

            } while (MAX_ITERATIONS_PER_FACTORY < mapInterfaces.size());

            getLog().info("Factory was too big, so it was splitted to " + --chunk + " chunks");
            _writeFactoryFile(factoryTemplate, mapInterfaces, chunk);
        }
        else {
            _writeFactoryFile(factoryTemplate, mapInterfaces, 0);
        }
    }

    private void _writeFactoryFile(Template factoryTemplate, Map<String, String> mapInterfaces, int chunkNumber) throws IOException, MojoFailureException {
        final String packagePath = BuildHelper.ensurePackageExists(this.jpaOutputDirectory.getAbsolutePath(), factoryPackageName);
        File factoryFile = BuildHelper.ensureFactoryFileExists(packagePath, 0);

        VelocityContext context = new VelocityContext();
        context.put("package", factoryPackageName);
        context.put("interfaces", mapInterfaces);
        context.put("chunkNumber", chunkNumber);

        StringWriter writer = new StringWriter();
        factoryTemplate.merge( context, writer );

        BuildHelper.writeContentToFile(writer.toString(), factoryFile);
    }

    private void _writeFactoryChunkFile(Template factoryChunkTemplate, Map<String, String> mapInterfaces, int chunk) throws IOException, MojoFailureException {
        final String packagePath = BuildHelper.ensurePackageExists(this.jpaOutputDirectory.getAbsolutePath(), factoryPackageName);
        File factoryChunkFile = BuildHelper.ensureFactoryFileExists(packagePath, chunk);

        VelocityContext context = new VelocityContext();
        context.put("package", factoryPackageName);
        context.put("interfaces", mapInterfaces);
        context.put("chunk", chunk);

        StringWriter writer = new StringWriter();
        factoryChunkTemplate.merge( context, writer );

        BuildHelper.writeContentToFile(writer.toString(), factoryChunkFile);
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

                String[] arrName = BuildHelper.getQualifiedName(jc);
                final String packagePath = BuildHelper.ensurePackageExists(this.jpaOutputDirectory.getAbsolutePath(), arrName[0]);

                File jpaFile = BuildHelper.getJpaFile(packagePath, arrName[1]);

                if (!jpaFile.exists()) {
                    jpaFile.createNewFile();

                    Map<String, FieldType> mapOfFields = BuildHelper.buildMapOfFields(jc, mapInterfaces);

                    VelocityContext context = new VelocityContext();
                    context.put("package", arrName[0]);
                    context.put("className", arrName[1]);
                    context.put("fieldMap", mapOfFields);
                    context.put("constructors", mapOfConstructors.get(arrName[0] + "." + arrName[1]));
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