package net.pibenchmark.pojo;

/**
 * Contains information about inner class
 */
public class InnerClass {

    public final String className;
    public final String sourceCode;

    public InnerClass(String className, String sourceCode) {
        this.className = className;
        this.sourceCode = sourceCode;
    }

    public String getClassName() {
        return className;
    }

    public String getSourceCode() {
        return sourceCode;
    }
}
