package datapool;

/** Параметры теста: -Dимя=значение (через Maven: mvn gatling:test -Drps=1000 ...). */
public final class Cfg {
    private Cfg() {}

    public static String str(String key, String def) {
        String v = System.getProperty(key);
        return v == null || v.isEmpty() ? def : v;
    }

    public static int num(String key, int def) {
        return Integer.parseInt(str(key, String.valueOf(def)));
    }

    public static double dbl(String key, double def) {
        return Double.parseDouble(str(key, String.valueOf(def)));
    }

    public static final String BASE = str("base", "http://localhost:8080");
    public static final String ENV = "load";
    public static final String[] READ_POOLS = {"p1", "p2", "p3"};
    public static final String LOCK_POOL = "p4";
    public static final String MIX_POOL = "p5";
    public static final int POOL_SIZE = num("poolSize", 10_000);
    public static final int MIX_SEED = num("mixSeed", 1_000);
}
