package net.pibenchmark;

import com.google.common.base.CaseFormat;
import com.google.common.collect.*;
import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaPackage;
import com.thoughtworks.qdox.model.impl.DefaultJavaClass;
import net.pibenchmark.pojo.FieldType;
import net.pibenchmark.pojo.InnerClass;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateFormatUtils;
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

    @Parameter( defaultValue = "id", readonly = true )
    private String fieldNameUsedAsIdentityName;

    @Parameter( defaultValue = "java.lang.Long", readonly = true )
    private String fieldNameUsedAsIdentityType;

    @Parameter( defaultValue = "SOAP", readonly = true )
    private String tableNamePrefix;

    // The set of postfixes. If a class has this part in its name, then this file will be ignored
    private final Set<String> setForbiddenNames = ImmutableSet.of("ObjectFactory", "Factory", "Impl");

    private JavaProjectBuilder builder;
    private File jpaOutputDirectory;

    // for the compilation performance, one factory can not contain more than this value of iterations
    private static final String JPA_SUFFIX = "JPA";
    private static final String FIELDS_SUFFIX = "Fields";
    // generation date in ISO 8601 standard
    private static final String generationDate = DateFormatUtils.ISO_DATETIME_TIME_ZONE_FORMAT.format(new Date());

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
        Template jpaStubTemplate = ve.getTemplate("JPAInterface.vm");
        Template udfParent = ve.getTemplate("UDFValueMapping.vm");

        try {

            // collect all interfaces to map "full stub name" <==> "fully qualified JPA name"
            final Map<String, String> mapInterfaces = builder.getClasses()
                    .stream()
                   /* .filter( (jc) -> jc.isInterface() ) */
                    .filter((jc) -> !jc.getName().endsWith("Factory") && !jc.getName().endsWith("Impl") && !jc.getName().endsWith("ObjectFactory"))
                    .collect(Collectors.toMap(
                            JavaClass::getCanonicalName,
                            jc -> BuildHelper.getQualifiedName(jc).replace("$", "JPA.") + "JPA"));

            // map "JPA class name" <==> "Set<full interface name>"
            final Map<String, Set<String>> mapOfConstructors = this.buildMapOfConstructors(mapInterfaces);

            // Map "soap interface/class" <==> "Fields file"
            final Map<String, String> mapOfFieldFiles = this.buildMapOfFieldProviders();

            // write all the JPA classes
            this.generateJpaClasses(jpaTemplate, udfParent, mapInterfaces, mapOfConstructors);

            // write the Field providers
            this.generateFieldProviders(fieldsTemplate, mapInterfaces, mapOfFieldFiles);

            // write the Factory class
            this.generateFactory(factoryTemplate, mapOfFieldFiles);

            // write IFieldProvider interface
            this.generateFieldProviderInterface(fieldProviderTemplate);

            // write IJpaStub interface
            this.generateJpaStubInterface(jpaStubTemplate);
        }
        catch (Exception e) {
            throw new MojoFailureException(e.getMessage());
        }

    }

    /**
     * Collect map "full name if Soap stub" <=> "full name of according Fields provider"
     *
     * @return map
     */
    private Map<String, String> buildMapOfFieldProviders() {
        return builder.getClasses()
                .parallelStream()
                .filter((jc) -> !setForbiddenNames.stream().anyMatch((forbiddenName) -> jc.getName().endsWith(forbiddenName)))
                .collect(
                        Collectors.toMap(JavaClass::getCanonicalName,
                        jc -> jc.getFullyQualifiedName().replace("$", "Fields.") + "Fields"));
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
        context.put("generationDate", generationDate);
        context.put("identityFieldType", this.fieldNameUsedAsIdentityType);

        StringWriter writer = new StringWriter();
        t.merge( context, writer );

        BuildHelper.writeContentToFile(writer.toString(), file);
    }

    /**
     * Create Interface for all the field containers
     *
     * @param t
     * @throws MojoFailureException
     */
    private void generateJpaStubInterface(Template t) throws MojoFailureException, IOException {

        final String packagePath = BuildHelper.ensurePackageExists(this.jpaOutputDirectory.getAbsolutePath(), this.fieldsPackageName);

        File file = BuildHelper.getFile(packagePath, "IJpaStub", "");
        VelocityContext context = new VelocityContext();
        context.put("package", fieldsPackageName);
        context.put("generationDate", generationDate);
        context.put("identityFieldType", this.fieldNameUsedAsIdentityType);
        context.put("identityFieldName", this.fieldNameUsedAsIdentityName);
        context.put("display", new DisplayTool());

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
     * Generate files for Field providers, containing name fields and other utility methods.
     * Used to build a request to Taleo and generate/initiate JPAs
     *
     * @param fieldsTemplate
     * @throws IOException
     * @throws MojoFailureException
     */
    private void generateFieldProviders(Template fieldsTemplate, Map<String, String> mapOfInterfaces, Map<String, String> mapOfFieldFiles) throws IOException, MojoFailureException {
        getLog().info("Generation of the Field Provider classes...");
        int cntCreatedFiles = 0;
        int cntSkippedFiles = 0;

        for (JavaClass jc : builder.getClasses()) {
            final boolean classNameShouldBeSkipped = setForbiddenNames.stream().anyMatch((forbiddenName) -> jc.getName().endsWith(forbiddenName));
            if (!jc.isInner() && !classNameShouldBeSkipped) {

                final String packagePath = BuildHelper.ensurePackageExists(this.jpaOutputDirectory.getAbsolutePath(), jc.getPackageName());

                File file = BuildHelper.getFile(packagePath, jc.getName(), FIELDS_SUFFIX);

                if (!file.exists()) {
                    file.createNewFile();

                    final String classBodyCode = this.getCodeOfInterfaceBody(false, fieldsTemplate, jc, mapOfInterfaces, mapOfFieldFiles)[1];
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
     * Recursive method that collects the list of fields and other useful methods to build up a queries
     *
     * @param isEmbedded
     * @param fieldsTemplate
     * @param jc
     *
     * @return String array:
     *  [0] - the first field from inner class
     *  [1] - the class code ready to be saved to a file
     */
    private String[] getCodeOfInterfaceBody(final boolean isEmbedded,
                                            final Template fieldsTemplate,
                                            final JavaClass jc,
                                            Map<String, String> mapOfInterfaces,
                                            Map<String, String> mapOfFieldFiles) {

        // map "field name" <==> "field type"
        final Map<String, FieldType> mapOfFieldTypes = BuildHelper.buildMapOfFields(jc,
                mapOfInterfaces,
                jc,
                getLog(),
                this.fieldNameUsedAsIdentityName,
                this.fieldNameUsedAsIdentityType,
                this.builder);

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
        final ImmutableList.Builder<InnerClass> lstInnerClassesBuilder = ImmutableList.builder();
        final ImmutableSet.Builder<String> setInnerClassNamesBuilder = ImmutableSet.builder();

        // map "inner class name" <==> "its first (obviously, only one) field name"
        final ImmutableMap.Builder<String, String> mapInnerClassFirstField = ImmutableMap.builder();

        if (!nestedClasses.isEmpty()) {
            for (JavaClass nestedClass : nestedClasses) {
                if (!nestedClass.getName().endsWith("Factory")) {
                    // render inner class and get the code
                    final String[] innerClass = this.getCodeOfInterfaceBody(true, fieldsTemplate, nestedClass, mapOfInterfaces, mapOfFieldFiles);
                    mapInnerClassFirstField.put(nestedClass.getName(), innerClass[0]);
                    lstInnerClassesBuilder.add(new InnerClass(nestedClass.getName(), innerClass[1] ));
                    setInnerClassNamesBuilder.add(nestedClass.getName());
                }
            }
        }

        // Check, whether current class has polymorphic fields, that are represented with abstract class
        // and marked with @XmlElements annotations, containing implementations
        final boolean hasPolymorphicField = mapOfFieldTypes.values().stream().anyMatch((type) -> type.isAbstract());

        VelocityContext context = new VelocityContext();
        context.put("package", jc.getPackageName());
        context.put("fieldsPackage", this.fieldsPackageName);
        context.put("factoryPackageName", this.factoryPackageName);
        context.put("identityFieldType", this.fieldNameUsedAsIdentityType);
        context.put("identityFieldName", this.fieldNameUsedAsIdentityName);
        context.put("className", jc.getName());
        context.put("isInner", jc.isInner());
        context.put("mapOfFields", mapOfFields);
        context.put("primitiveFields", setOfPrimitives);
        context.put("fieldsCount", mapOfFields.size());
        context.put("innerClasses", lstInnerClassesBuilder.build());
        context.put("innerClassNames", setInnerClassNamesBuilder.build());
        context.put("innerClassFields", mapInnerClassFirstField.build());
        context.put("display", new DisplayTool());
        context.put("sorter", new SortTool());
        context.put("isEmbedded", isEmbedded);
        context.put("generationDate", generationDate);
        context.put("jpaClass", mapOfInterfaces.get(jc.getCanonicalName()));
        context.put("soapStubClass", jc.getCanonicalName().replace("$", "."));
        context.put("mapOfFieldTypes", mapOfFieldTypes);
        context.put("mapOfFieldFiles", mapOfFieldFiles);
        context.put("hasIdentField", setOfPrimitives.stream().anyMatch((field) -> field.equalsIgnoreCase(this.fieldNameUsedAsIdentityName)));
        context.put("hasPolymorphicField", hasPolymorphicField);

        StringWriter writer = new StringWriter();
        fieldsTemplate.merge( context, writer );

        // get the very first field of current class. It will be used to build the path containing inner classes
        final Iterator<String> iterator = mapOfFieldTypes.keySet().iterator();
        final String strFirstFieldOfInnerClass = iterator.hasNext() ? StringUtils.capitalize(iterator.next()) : "";

        return new String[]{ strFirstFieldOfInnerClass, writer.toString() };
    }

    private void generateFactory(Template factoryTemplate, Map<String, String> mapFieldFiles) throws IOException, MojoFailureException {
        final String packagePath = BuildHelper.ensurePackageExists(this.jpaOutputDirectory.getAbsolutePath(), factoryPackageName);
        File factoryFile = BuildHelper.ensureFactoryFileExists(packagePath, 0);

        VelocityContext context = new VelocityContext();
        context.put("package", factoryPackageName);
        context.put("interfaces", mapFieldFiles);
        context.put("fieldsPackage", this.fieldsPackageName);
        context.put("generationDate", generationDate);

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
    private void generateJpaClasses(Template t, Template udfParent, Map<String, String> mapInterfaces, Map<String, Set<String>> mapOfConstructors) throws Exception {
        getLog().info("Generation of the JPA objects...");
        int cntCreatedFiles = 0;
        int cntSkippedFiles = 0;

        for (JavaClass jc : builder.getClasses()) {
            if (/*jc.isInterface() && */!jc.isInner()) {

                final String packageName = jc.getPackageName();
                final String packagePath = BuildHelper.ensurePackageExists(this.jpaOutputDirectory.getAbsolutePath(), packageName);

                if (jc.getName().equals("UDF")) {
                    // hardcoded stuff. I would be happy to generalize it.
                    this.generateUDFParenClass(udfParent, packageName);
                }

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
     * Write hardcoded file: parent file for UDF collection
     *
     * @param udfParent
     * @throws Exception
     */
    private void generateUDFParenClass(Template udfParent, String packageName) throws Exception {
        final String packagePath = BuildHelper.ensurePackageExists(this.jpaOutputDirectory.getAbsolutePath(), packageName);

        File file = BuildHelper.getFile(packagePath, "UDFValueMapping", "");
        VelocityContext context = new VelocityContext();
        context.put("package", packageName);

        StringWriter writer = new StringWriter();
        udfParent.merge(context, writer );

        BuildHelper.writeContentToFile(writer.toString(), file);
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

        final Map<String, FieldType> mapOfFields = BuildHelper.buildMapOfFields(jc,
                mapInterfaces,
                mostUpperClass,
                getLog(),
                this.fieldNameUsedAsIdentityName,
                this.fieldNameUsedAsIdentityType,
                this.builder);

        // map "field in CamelCase" <==> "field on LOWER_CASE"
        final Map<String, String> mapOfCamelFields = mapOfFields.keySet()
                .parallelStream()
                .sorted()
                .collect(Collectors.toMap(
                        Function.<String>identity(),
                        (field) -> CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, field) ));

        // build list of "strangers"
        final Map<String, FieldType> mapOfStrangers = this.buildMapOfInnerClassesBelongingToAnotherEntity(mostUpperClass.getFullyQualifiedName(), mapOfFields);


        // build the bodies of inner classes
        final List<InnerClass> lstInnerClasses = this.getListOfInnerClasses(t, mapInterfaces, mapOfConstructors, jc, mostUpperClass, mapOfStrangers);

        modifyContextForStrangerTypes(mapOfFields,mapOfStrangers,jc.getName());

        // in case of inner classes we have to use prefixes for fields in order to avoid duplicates.
        final String fieldPrefix = isEmbedded ?
                StringUtils.uncapitalize(jc.getName()) + "_"
                : "";

        // check whether current class has Ident field
        final boolean hasIdentField = mapOfFields.keySet()
                .parallelStream()
                .anyMatch((field) -> field.equalsIgnoreCase(this.fieldNameUsedAsIdentityName));

        //extends
        final String parentClass = (null == jc.getSuperJavaClass()) ?
                java.lang.Object.class.getTypeName() :
                mapInterfaces.getOrDefault(jc.getSuperJavaClass().getGenericFullyQualifiedName(), java.lang.Object.class.getTypeName());


        VelocityContext context = new VelocityContext();
        context.put("isEmbedded", isEmbedded);
        context.put("package", jc.getPackageName());
        context.put("className", jc.getName());
        context.put("fieldMap", mapOfFields);
        context.put("fieldCamelMap", mapOfCamelFields);
        context.put("innerClasses", lstInnerClasses);
        context.put("display", new DisplayTool());
        context.put("generationDate", generationDate);
        context.put("identityFieldName", this.fieldNameUsedAsIdentityName);
        context.put("identityFieldType", this.fieldNameUsedAsIdentityType);
        context.put("fieldsPackage", this.fieldsPackageName);
        context.put("tableNamePrefix", this.tableNamePrefix);
        context.put("fieldPrefix", fieldPrefix);
        context.put("hasIdentField", hasIdentField);
        context.put("parentClass", parentClass);


        if (isEmbedded) {
            final String className = jc.getFullyQualifiedName().replace("$",".");
            context.put("constructors", className);
        }
        else {
            final String className = jc.getPackageName() + "." + jc.getName();
            context.put("constructors", className);
        }

        StringWriter writer = new StringWriter();
        t.merge( context, writer );

        return writer.toString();
    }

    void modifyContextForStrangerTypes(Map<String, FieldType> mapOfFields, Map<String, FieldType> mapOfStrangers, String name) {
        mapOfFields.keySet()
                .stream()
                .filter((key) -> mapOfStrangers.containsKey(key))
                .map((key) -> {
                    mapOfFields.get(key)
                            .setOriginalTypeName(mapOfFields.get(key).getOriginalTypeName().replaceFirst("\\.[A-Z][\\w]+\\.", "." + name + "."));
                    return key;
                })
                .count();
    }

    /**
     * Fix for the inheritance: there are cases, when a class has references to an inner class, belonging to another class.
     * This causes troubles in JPA, because of PK and FK constraints. This method finds out which fields are inner classes,
     * belonging to another class.
     *
     * @param currentClassFQN
     * @param mapOfFields
     * @return
     */
    Map<String, FieldType> buildMapOfInnerClassesBelongingToAnotherEntity(final String currentClassFQN, Map<String, FieldType> mapOfFields) {
        return  mapOfFields.keySet()
                .stream()
                .filter( (key) -> (mapOfFields.get(key).isInnerClass() || mapOfFields.get(key).isGenericInnerClass()) && !mapOfFields.get(key).hasIdentField())
                .filter( (key) -> !mapOfFields.get(key).getOriginalTypeName().startsWith(currentClassFQN))
                .collect(Collectors.toMap(
                        Function.<String>identity(),
                        mapOfFields::get
                ));
    }

    /**
     * Collect all the internal classes body code. In other words, it generates a code for each class
     * and return all of them as a collection
     *
     * @param jc
     *
     * @return
     */
    private List<InnerClass> getListOfInnerClasses(Template t, Map<String, String> mapInterfaces, Map<String, Set<String>> mapOfConstructors,
                                                   JavaClass jc, JavaClass mostUpperClass, Map<String, FieldType> mapOfStrangers) {

        final List<JavaClass> nestedClasses = jc.getNestedClasses();

        final List<DefaultJavaClass> listOfStrangers = mapOfStrangers
                .keySet()
                .parallelStream()
                .map((key) -> (DefaultJavaClass) builder.getClassByName(mapOfStrangers.get(key).getOriginalTypeName()))
                .collect(Collectors.toList());

        //nestedClasses.addAll(listOfStrangers);

        if (!nestedClasses.isEmpty()) {
            final ImmutableList.Builder<InnerClass> listBuilder = ImmutableList.builder();
            for (JavaClass nestedClass : nestedClasses) {
                if (/*nestedClass.isInterface() &&*/ !nestedClass.getName().endsWith("Factory")) {
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