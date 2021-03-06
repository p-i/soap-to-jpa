package net.pibenchmark.pojo;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import net.pibenchmark.CastType;

import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by ilja on 06/10/14.
 */
public class FieldType {

    public static final byte PRIMITIVE = 0;
    public static final byte ARRAY_OF_PRIMITIVES = 1;
    public static final byte ARRAY_OF_COMPLEX_TYPES = 2;
    public static final byte ARRAY_OF_INNER_CLASSES = 3;
    public static final byte COLLECTION = 4;
    public static final byte COMPLEX_TYPE = 5;
    public static final byte INNER_CLASS = 6;

    private final String typeName; // JPA type. For a collection it also refers to JPA generic type
    private boolean isGenericInnerClass; // whether class that we refer (this.typeName) is inner class or not
    private final String originalTypeSimpleName; // simple name
    private final byte typeKind;
    private final boolean hasIdentField; // whether current class has ident field (ID)
    private final int cntFields; // count of fields for complex type. For primitives, arrays, collections it is «0»
    private final boolean isJpa; // is JPA object (having populate()) method or any other class (for example, Serializable)

    private String originalTypeName; // FQN
    // if original type does not match target type
    private boolean isShouldBeCasted;
    private CastType castType;

    private boolean isAbstract = false; // current class is too generic and it is used in polymorphic fields
    private Set<String> setImplementations;

    /**
     *
     *
     * @param b
     * @param fullTypeName - the full type of JPA class
     * @param originalTypeName - the full name of origin type (stub)
     * @param originalTypeSimpleName - the simple name of a stub class (without package)
     * @param isHavingIdentField
     * @param fieldsCount
     */
    public FieldType(byte b, String fullTypeName, String originalTypeName, String originalTypeSimpleName, boolean isHavingIdentField, int fieldsCount, boolean isJpa) {
        this.typeKind = b;
        this.typeName = fullTypeName;
        this.originalTypeName = originalTypeName;
        this.isShouldBeCasted = false;
        this.originalTypeSimpleName = originalTypeSimpleName;
        this.hasIdentField = isHavingIdentField;
        this.cntFields = fieldsCount;
        this.isJpa = isJpa;
    }

    public String getTypeName() {
        return typeName;
    }

    public byte getTypeKind() {
        return typeKind;
    }

    public String getOriginalTypeName() {
        return originalTypeName;
    }

    public String getOriginalTypeSimpleName() { return originalTypeSimpleName; }

    public void setShouldBeCasted(boolean isShouldBeCasted) {
        this.isShouldBeCasted = isShouldBeCasted;
    }

    public void cast(String from, String to) {
        this.castType = CastType.of(from,to);
    }

    public CastType getCastType() {
        return castType;
    }

    public boolean hasIdentField() { return hasIdentField; }

    public int getCountOfFields() { return cntFields; }

    public void setOriginalTypeName(String originalTypeName) {
        this.originalTypeName = originalTypeName;
    }

    /**
     * Return type ready to be rendered.
     *
     * @return
     */
    public String render() {
        switch (this.typeKind) {

            case ARRAY_OF_COMPLEX_TYPES:
            case ARRAY_OF_INNER_CLASSES:
                return List.class.getTypeName() + "<" + this.typeName + ">";

            case ARRAY_OF_PRIMITIVES:
                return this.typeName + "[]";

            case COLLECTION:
                return List.class.getTypeName() + "<" + this.typeName + ">";

            default:
                return this.typeName;
        }
    }


    public Set<String> getImplementations() {
        return this.setImplementations;
    }

    public boolean isPrimitive() { return this.typeKind == PRIMITIVE; }
    public boolean isArrayOfPrimitives() {
        return this.typeKind == ARRAY_OF_PRIMITIVES;
    }
    public boolean isArrayOfComplextType() {
        return this.typeKind == ARRAY_OF_COMPLEX_TYPES;
    }
    public boolean isArrayOfInnerClasses() {
        return this.typeKind == ARRAY_OF_INNER_CLASSES;
    }
    public boolean isArray() {
        return (this.typeKind == ARRAY_OF_PRIMITIVES || this.typeKind == ARRAY_OF_COMPLEX_TYPES || this.typeKind == ARRAY_OF_INNER_CLASSES);
    }
    public boolean isCollection() {
        return this.typeKind == COLLECTION;
    }
    public boolean isComplexType() {
        return this.typeKind == COMPLEX_TYPE;
    }
    public boolean isInnerClass() {
        return this.typeKind == INNER_CLASS;
    }

    public boolean isDefined() {
        return this.typeName != null;
    }
    public boolean isString() { return String.class.getTypeName().equals(this.typeName); }
    public boolean isShouldBeCasted() { return this.isShouldBeCasted; }
    public boolean isAbstract() {
        return isAbstract;
    }
    public boolean isGenericInnerClass() {
        return isGenericInnerClass;
    }
    public boolean isJpa() { return isJpa; }

    public void setGenericInnerClass(boolean isGenericInnerClass) {
        this.isGenericInnerClass = isGenericInnerClass;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FieldType fieldType = (FieldType) o;

        if (typeKind != fieldType.typeKind) return false;
        if (!typeName.equals(fieldType.typeName)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = typeName.hashCode();
        result = 31 * result + (int) typeKind;
        return result;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("typeName", typeName)
                .add("originalTypeName", originalTypeName)
                .add("typeKind", typeKind)
                .add("hasIdentField", hasIdentField)
                .add("countOfFields", cntFields)
                .add("isInnerClass", isInnerClass())
                .add("isGenericInnerClass", isGenericInnerClass)
                .toString();
    }

    public void addImplementations(Set<String> setImps) {
        this.setImplementations = setImps;
        this.isAbstract = !this.setImplementations.isEmpty();
    }
}
