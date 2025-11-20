package cn.bitsleep.tdl.domain;

public enum TodoStatus {
    ACTIVE(0),
    COMPLETED(1),
    TRASHED(2);

    public final int code;
    TodoStatus(int code) { this.code = code; }

    public static TodoStatus fromCode(int code) {
        for (var v : values()) if (v.code == code) return v;
        throw new IllegalArgumentException("Unknown status code: " + code);
    }
}
