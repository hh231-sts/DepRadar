package io.depradar.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

public final class Values {
    private Values() {}
    public static String id() { return UUID.randomUUID().toString(); }
    public static String hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    public static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static void require(boolean condition, String reason) { if (!condition) throw Problem.bad(reason); }
    public record Page<T>(List<T> items, String nextCursor) {}
    public static <T> Page<T> page(List<T> rows, java.util.function.Function<T,String> id) {
        boolean more = rows.size() > 50;
        List<T> items = more ? rows.subList(0, 50) : rows;
        return new Page<>(items, more ? id.apply(items.getLast()) : null);
    }
}
