package net.pibenchmark;

import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.JavaClass;
import net.pibenchmark.pojo.FieldType;
import net.pibenchmark.pojo.InnerClass;
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
import org.apache.velocity.tools.generic.SortTool;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import java.util.function.Function;
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

    @Parameter( defaultValue = "org.apache.maven.soap.jpa.fields", readonly = true )
    private String fieldsPackageName;

    private JavaProjectBuilder builder;
    private File jpaOutputDirectory;

    // for the compilation performance, one factory can not contain more than this value of iterations
    private static final int MAX_ITERATIONS_PER_FACTORY = 50;
    private static final String JPA_SUFFIX = "JPA";
    private static final String FIELDS_SUFFIX = "Fields";

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
     * {@inheritDoc}
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        this.setUpPlugin();

        VelocityEngine ve = this.setUpVelocity();

        Template jpaTemplate = ve.getTemplate("JpaEntityTemplate.vm");
        Template factoryTemplate = ve.getTemplate("FactoryTemplate.vm");
        Template fieldsTemplate = ve.getTemplate("FieldsTemplate.vm");
        Template fieldProviderTemplate = ve.getTemplate("FieldsInterface.vm");

        try {

            // collect all interfaces to map "full interface name" <==> "fully qualified JPA name"
            final Map<String, String> mapInterfaces = builder.getClasses()
                    .stream()
                    .filter( (jc) -> jc.isInterface() )
                    .filter( (jc) -> !jc.getName().endsWith("Factory") && !jc.getName().endsWith("Impl") )
                    .collect(Collectors.toMap(
                            JavaClass::getCanonicalName,
                            jc -> BuildHelper.getQualifiedName(jc).replace("$", "JPA.") + "JPA"));

            // map "JPA class name" <==> "Set<full interface name>"
            final Map<String, Set<String>> mapOfConstructors = this.buildMapOfConstructors(mapInterfaces);

            // write all the JPA classes
            this.generateJpaClasses(jpaTemplate, mapInterfaces, mapOfConstructors);

            // write the Fields constants
            this.generateFieldConstants(fieldsTemplate, mapInterfaces);

            // write the Factory class
            this.generateFactory(factoryTemplate, mapInterfaces);

            // write IFieldProvider interface
            this.generateFieldProviderInterface(fieldProviderTemplate);
        }
        catch (Exception e) {
            throw new MojoFailureException(e.getMessage());
        }

    }

    /**
     * Create Interface for all the field containers
     *
     * @param t
     * @throws MojoFailureException
     */
    private void generateFieldProviderInterface(Template t) throws MojoFailureException, IOException {

        final String packagePath = BuildHelper.ensurePackageExists(this.jpaOutputDirectory.getAbsolutePath(), this.fieldsPackageName);

        File file = BuildHelper.getFile(packagePath, "IFieldProvider", "");
        VelocityContext context = new VelocityContext();
        context.put("package", fieldsPackageName);

        StringWriter writer = new StringWriter();
        t.merge( context, writer );

        BuildHelper.writeContentToFile(writer.toString(), file);
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
        mapInterfaces.forEach((interfaceName, JpaClassName) -> {
            if (mapOfConstructors.containsKey(JpaClassName)) {
                mapOfConstructors.get(JpaClassName).add(interfaceName);
            } else {
                mapOfConstructors.put(JpaClassName, Sets.newHashSet(interfaceName));
            }
        });
        return mapOfConstructors;
    }

    /**
     * Generate files (interfaces) with only constants. This is the name of fields.
     * Used to build a request to Taleo.
     *
     * @param fieldsTemplate
     * @throws IOException
     * @throws MojoFailureException
     */
    private void generateFieldConstants(Template fieldsTemplate, Map<String, String> mapOfInterfaces) throws IOException, MojoFailureException {
        getLog().info("Generation of the Field objects...");
        int cntCreatedFiles = 0;
        int cntSkippedFiles = 0;

        for (JavaClass jc : builder.getClasses()) {
            if (jc.isInterface() && !jc.isInner()) {

                final String packagePath = BuildHelper.ensurePackageExists(this.jpaOutputDirectory.getAbsolutePath(), this.fieldsPackageName);

                File file = BuildHelper.getFile(packagePath, jc.getName(), FIELDS_SUFFIX);

                if (!file.exists()) {
                    file.createNewFile();

                    final String classBodyCode = this.getCodeOfInterfaceBody(false, fieldsTemplate, jc, mapOfInterfaces);
                    BuildHelper.writeContentToFile(classBodyCode, file);

                    cntCreatedFiles++;
                }
                else {
                    cntSkippedFiles++;
                }
            }
        }
        getLog().info(cntCreatedFiles + " files were generated and " + cntSkippedFiles + " were skipped");
    }

    /**
     * Recursive method that collects the list of fields and
     * @param isEmbedded
     * @param fieldsTemplate
     * @param jc
     * @return
     */
    private String getCodeOfInterfaceBody(final boolean isEmbedded, final Template fieldsTemplate, final JavaClass jc, Map<String, String> mapOfInterfaces) {

        // map "field name" <==> "field type"
        final Map<String, FieldType> mapOfFieldTypes = BuildHelper.buildMapOfFields(jc, mapOfInterfaces, jc, getLog());

        // map "field on LOWER_CASE" <==> "field in CamelCase"
        final Map<String, String> mapOfFields = mapOfFieldTypes.keySet()
                .parallelStream()
                .sorted()
                .collect(Collectors.toMap(
                        (field) -> CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, field),
                        Function.<String>identity()));

        // set of fields with a primitive type (String, numbers...)
        final Set<String> setOfPrimitives = mapOfFieldTypes.keySet()
                .parallelStream()
                .filter((field) -> mapOfFieldTypes.get(field).isPrimitive())
                .collect(Collectors.toSet());

        final List<JavaClass> nestedClasses = jc.getNestedClasses();
        final ImmutableList.Builder<InnerClass> listBuilder = ImmutableList.builder();
        if (!nestedClasses.isEmpty()) {
            for (JavaClass nestedClass : nestedClasses) {
                if (nestedClass.isInterface() && !nestedClass.getName().endsWith("Factory")) {
                    // render inner class and get the code
                    final String codeOfInnerClassBody = this.getCodeOfInterfaceBody(true, fieldsTemplate, nestedClass, mapOfInterfaces);
                    listBuilder.add(new InnerClass(nestedClass.getName(), codeOfInnerClassBody));
                }
            }
        }

        VelocityContext context = new VelocityContext();
        context.put("package", this.fieldsPackageName);
        context.put("className", jc.getName());
        context.put("mapOfFields", mapOfFields);
        context.put("primitiveFields", setOfPrimitives);
        context.put("fieldsCount", mapOfFields.size());
        context.put("innerClasses", listBuilder.build());
        context.put("display", new DisplayTool());
        context.put("sorter", new SortTool());
        context.put("isEmbedded", isEmbedded);

        StringWriter writer = new StringWriter();
        fieldsTemplate.merge( context, writer );

        return writer.toString();
    }

    private void generateFactory(Template factoryTemplate, Map<String, String> mapInterfaces) throws IOException, MojoFailureException {
        final String packagePath = BuildHelper.ensurePackageExists(this.jpaOutputDirectory.getAbsolutePath(), factoryPackageName);
        File factoryFile = BuildHelper.ensureFactoryFileExists(packagePath, 0);

        VelocityContext context = new VelocityContext();
        context.put("package", factoryPackageName);
        context.put("interfaces", mapInterfaces);

        StringWriter writer = new StringWriter();
        factoryTemplate.merge( context, writer );

        BuildHelper.writeContentToFile(writer.toString(), factoryFile);
    }

    /**
     * Generate JPA class for each interface, found in the generate-sources directory. Subclasses will
     * be stored in a separate subpackage "inners"
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
            if (jc.isInterface() && !jc.isInner()) {

                final String packageName = jc.getPackageName();
                final String packagePath = BuildHelper.ensurePackageExists(this.jpaOutputDirectory.getAbsolutePath(), packageName);

                File jpaFile = BuildHelper.getFile(packagePath, jc.getName(), JPA_SUFFIX);

                if (!jpaFile.exists()) {
                    jpaFile.createNewFile();

                    final String classBodyCode = this.getCodeOfClassBody(false, t, mapInterfaces, mapOfConstructors, jc, jc);

                    BuildHelper.writeContentToFile(classBodyCode, jpaFile);

                    cntCreatedFiles++;
                }
                else {
                    cntSkippedFiles++;
                }
            }
        }
        getLog().info(cntCreatedFiles + " files were generated and " + cntSkippedFiles + " were skipped");
    }

    /**
     * Renders the body code of a given class and returns it as String.
     * It can be parent class or embedded (inner) class.
     *
     * @param t
     * @param mapInterfaces
     * @param mapOfConstructors
     * @param jc
     * @return
     */
    private String getCodeOfClassBody(boolean isEmbedded, Template t, Map<String, String> mapInterfaces, Map<String, Set<String>> mapOfConstructors, JavaClass jc, JavaClass mostUpperClass) {

        final Map<String, FieldType> mapOfFields = BuildHelper.buildMapOfFields(jc, mapInterfaces, mostUpperClass, getLog());
        final List<InnerClass> lstInnerClasses = this.getListOfInnerClasses(t, mapInterfaces, mapOfConstructors, jc, mostUpperClass);

        VelocityContext context = new VelocityContext();
        context.put("isEmbedded", isEmbedded);
        context.put("package", jc.getPackageName());
        context.put("className", jc.getName());
        context.put("fieldMap", mapOfFields);
        context.put("innerClasses", lstInnerClasses);
        context.put("display", new DisplayTool());
        if (isEmbedded) {
            final String className = jc.getFullyQualifiedName().replace("$","JPA.") + "JPA";
            context.put("constructors", mapOfConstructors.get(className));
        }
        else {
            final String className = jc.getPackageName() + "." + jc.getName() + "JPA";
            context.put("constructors", mapOfConstructors.get(className));
        }

        StringWriter writer = new StringWriter();
        t.merge( context, writer );

        return writer.toString();
    }

    /**
     * Collect all the internal classes body code. In other words, it generates a code for each class
     * and return all of them as a collection
     *
     * @param jc
     *
     * @return
     */
    private List<InnerClass> getListOfInnerClasses(Template t, Map<String, String> mapInterfaces, Map<String, Set<String>> mapOfConstructors, JavaClass jc, JavaClass mostUpperClass) {
        final List<JavaClass> nestedClasses = jc.getNestedClasses();
        if (!nestedClasses.isEmpty()) {
            final ImmutableList.Builder<InnerClass> listBuilder = ImmutableList.builder();
            for (JavaClass nestedClass : nestedClasses) {
                if (nestedClass.isInterface() && !nestedClass.getName().endsWith("Factory")) {
                    // render inner class and get the code
                    final String codeOfInnerClassBody = this.getCodeOfClassBody(true, t, mapInterfaces, mapOfConstructors, nestedClass, mostUpperClass);
                    listBuilder.add(new InnerClass(nestedClass.getName(), codeOfInnerClassBody));
                }
            }
            return listBuilder.build();
        }

        return Lists.newArrayList();
    }

}