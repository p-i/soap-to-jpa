package net.pibenchmark.pojo;

import com.google.common.base.Objects;

/**
 * Created by ilja on 06/10/14.
 */
public class FieldType {

    private String type;
    private boolean isPrimitive;

    public FieldType(boolean b, String typeName) {
        this.type = typeName;
        this.isPrimitive = b;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public boolean isPrimitive() {
        return isPrimitive;
    }

    public void setPrimitive(boolean isPrimitive) {
        this.isPrimitive = isPrimitive;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FieldType fieldType = (FieldType) o;

        if (isPrimitive != fieldType.isPrimitive) return false;
        if (!type.equals(fieldType.type)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = type.hashCode();
        result = 31 * result + (isPrimitive ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("type", type)
                .add("isPrimitive", isPrimitive)
                .toString();
    }
}
