package net.pibenchmark;

import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Table;

/**
 * Created by ilja on 13/11/14.
 */
public enum CastType {
    STRING_TO_LONG,
    STRING_TO_INT,
    STRING_TO_FLOAT;

    private static Table<String, String, CastType> hashTable = ImmutableTable.
            <String, String, CastType> builder()
            .put(String.class.getTypeName(), Long.class.getTypeName(), STRING_TO_LONG)
            .put(String.class.getTypeName(), Integer.class.getTypeName(), STRING_TO_INT)
            .put(String.class.getTypeName(), Float.class.getTypeName(), STRING_TO_FLOAT)
            .build();

    public static CastType of(String from, String to) {
        return hashTable.get(from, to);
    }

}
