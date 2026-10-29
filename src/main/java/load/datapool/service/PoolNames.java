package load.datapool.service;

/** Проверка имён env и pool. Они попадают в текст SQL, поэтому допускаются только буквы, цифры и подчёркивание. */
public final class PoolNames {
    private PoolNames() {}

    public static final int MAX_LENGTH = 64;

    public static boolean valid(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_LENGTH) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    public static void require(String env, String pool) {
        if (!valid(env)) throw new PoolException("Bad env name '" + shorten(env) + "': only letters, digits and underscore are allowed", PoolException.BAD_NAME);
        if (!valid(pool)) throw new PoolException("Bad pool name '" + shorten(pool) + "': only letters, digits and underscore are allowed", PoolException.BAD_NAME);
    }

    private static String shorten(String s) {
        if (s == null) return "null";
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }
}
