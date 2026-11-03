package io.depradar.shared;

import org.springframework.http.HttpStatus;

public class Problem extends RuntimeException {
    private final HttpStatus status;
    public Problem(HttpStatus status, String message) { super(message); this.status = status; }
    public HttpStatus status() { return status; }
    public static Problem bad(String reason) { return new Problem(HttpStatus.BAD_REQUEST, reason); }
    public static Problem missing(String reason) { return new Problem(HttpStatus.NOT_FOUND, reason); }
    public static Problem conflict(String reason) { return new Problem(HttpStatus.CONFLICT, reason); }
    public static Problem forbidden() { return new Problem(HttpStatus.FORBIDDEN, "无权访问此资源或执行此操作"); }
}
