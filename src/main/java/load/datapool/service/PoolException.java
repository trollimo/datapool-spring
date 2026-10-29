package load.datapool.service;

/** Ошибка сценария использования: статус и текст ответа клиенту, плюс подпись для метрики (details). */
public class PoolException extends RuntimeException {

    /** Подпись метрики для неверных имён: такие запросы в метриках не учитываются (иначе имена из запроса плодили бы серии). */
    public static final String BAD_NAME = "Bad name";

    private final int status;
    private final String detail;

    public PoolException(String message, String detail) {
        this(400, message, detail);
    }

    public PoolException(int status, String message, String detail) {
        super(message);
        this.status = status;
        this.detail = detail;
    }

    public int getStatus() { return status; }

    public String getDetail() { return detail; }

    public boolean isBadName() { return BAD_NAME.equals(detail); }
}
