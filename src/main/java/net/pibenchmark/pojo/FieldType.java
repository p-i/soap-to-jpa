package net.pibenchmark.pojo;

import com.google.common.base.Objects;

import java.util.List;

/**
 * Created by ilja on 06/10/14.
 */
public class FieldType {

    public static final byte PRIMITIVE = 0;
    public static final byte ARRAY_OF_PRIMITIVES = 1;
    public static final byte ARRAY_OF_COMPLEX_TYPES = 2;
    public static final byte COLLECTION = 3;
    public static final byte COMPLEX_TYPE = 4;

    private final String typeName;
    private final String originalTypeName;
    private final byte typeKind;

    public FieldType(byte b, String typeName, String originalTypeName) {
        this.typeKind = b;
        this.typeName = typeName;
        this.originalTypeName = originalTypeName;
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

    /**
     * Return type ready to be rendered.
     *
     * @return
     */
    public String render() {
        switch (this.typeKind) {

            case ARRAY_OF_COMPLEX_TYPES:
                return List.class.getTypeName() + "<" + this.typeName + ">";

            case ARRAY_OF_PRIMITIVES:
                return this.typeName + "[]";

            case COLLECTION:
                return List.class.getTypeName();

            default:
                return this.typeName;
        }
    }

    public boolean isPrimitive() {
        return this.typeKind == PRIMITIVE;
    }
    public boolean isArrayOfPrimitives() {
        return this.typeKind == ARRAY_OF_PRIMITIVES;
    }
    public boolean isArrayOfComplextType() {
        return this.typeKind == ARRAY_OF_COMPLEX_TYPES;
    }
    public boolean isCollection() {
        return this.typeKind == COLLECTION;
    }
    public boolean isComplexType() {
        return this.typeKind == COMPLEX_TYPE;
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
        return Objects.toStringHelper(this)
                .add("typeName", typeName)
                .add("originalTypeName", originalTypeName)
                .add("typeKind", typeKind)
                .toString();
    }
}
