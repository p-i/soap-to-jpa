package net.pibenchmark;

import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.JavaClass;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;

import java.io.File;
import java.io.IOException;

/**
 * @goal soap-to-jpa
 * @phase generate-sources
 */
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

        try {
            for (JavaClass jc : builder.getClasses()) {
                if (jc.isInterface()) {

                    String packagePath = ensurePackageExists(jc);
                    File jpaFile = ensureFileExists(packagePath, jc);
                    BuildJPAFileContent.buildJPAfor(jc, jpaFile);
                }
            }
        }
        catch (Exception e) {
            throw new MojoFailureException(e.getMessage());
        }

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
     * @param jc
     */
    private String ensurePackageExists(JavaClass jc) {

        final String absPackagePath = new StringBuilder()
                .append(this.jpaOutputDirectory.getAbsolutePath())
                .append(File.separator)
                .append(jc.getPackageName().replace(".", File.separator))
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
    private File ensureFileExists(final String strPackagePath, JavaClass jc) throws IOException {

        final String absPathJpaFile = new StringBuilder()
                .append(strPackagePath)
                .append(File.separator)
                .append(jc.getName())
                .append("JPA.java")
                .toString();

        File newJpaClass = new File(absPathJpaFile);
        if (!newJpaClass.exists()) newJpaClass.createNewFile();
        return newJpaClass;
    }
}