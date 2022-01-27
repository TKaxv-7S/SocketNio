package com.tk.socket;

/**
 * 业务异常
 */
public class BusinessException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final Integer code;

    private final String message;

    private final Object data;

    private final Boolean isTranslate;

    public BusinessException() {
        this("系统错误");
    }

    public BusinessException(String message) {
        this.message = message;
        this.code = 500;
        this.data = null;
        this.isTranslate = true;
    }

    public BusinessException(String message, Object data) {
        this.message = message;
        this.code = 500;
        this.data = data;
        this.isTranslate = true;
    }

    public BusinessException(String message, Integer code) {
        this.message = message;
        this.code = code == null ? 500 : code;
        this.data = null;
        this.isTranslate = true;
    }

    public BusinessException(String message, Integer code, Object data) {
        this.message = message;
        this.code = code == null ? 500 : code;
        this.data = data;
        this.isTranslate = true;
    }

    public BusinessException(String message, Integer code, Object data, boolean isTranslate) {
        this.message = message;
        this.code = code == null ? 500 : code;
        this.data = data;
        this.isTranslate = isTranslate;
    }

    public BusinessException(Throwable e, String message) {
        super(message, e);
        this.message = message;
        this.code = 500;
        this.data = null;
        this.isTranslate = true;
    }

    public BusinessException(Throwable e, String message, Integer code) {
        super(message, e);
        this.message = message;
        this.code = code == null ? 500 : code;
        this.data = null;
        this.isTranslate = true;
    }

    public BusinessException(Throwable e, String message, Integer code, Object data, boolean isTranslate) {
        super(message, e);
        this.message = message;
        this.code = code == null ? 500 : code;
        this.data = data;
        this.isTranslate = isTranslate;
    }

    public static BusinessException newException(String message) {
        return new BusinessException(message, 500, null, true);
    }

    public static BusinessException newExceptionTranslate(String message, boolean isTranslate) {
        return new BusinessException(message, 500, null, isTranslate);
    }

    public static BusinessException newException(String message, Object data) {
        return new BusinessException(message, 500, data, true);
    }

    public static BusinessException newExceptionTranslate(String message, Object data, boolean isTranslate) {
        return new BusinessException(message, 500, data, isTranslate);
    }

    public static BusinessException newException(String message, Integer code) {
        return new BusinessException(message, code, null, true);
    }

    public static BusinessException newExceptionTranslate(String message, Integer code, boolean isTranslate) {
        return new BusinessException(message, code, null, isTranslate);
    }

    public static BusinessException newException(String message, Integer code, Object data) {
        return new BusinessException(message, code, data, true);
    }

    public static BusinessException newExceptionTranslate(String message, Integer code, Object data, boolean isTranslate) {
        return new BusinessException(message, code, data, isTranslate);
    }

    public static BusinessException newException(Throwable e, String message) {
        return new BusinessException(e, message, 500, null, true);
    }

    public static BusinessException newExceptionTranslate(Throwable e, String message, boolean isTranslate) {
        return new BusinessException(e, message, 500, null, isTranslate);
    }

    public static BusinessException newException(Throwable e, String message, Integer code) {
        return new BusinessException(e, message, code, null, true);
    }

    public static BusinessException newExceptionTranslate(Throwable e, String message, Integer code, boolean isTranslate) {
        return new BusinessException(e, message, code, null, isTranslate);
    }

    @Override
    public String getMessage() {
        return message;
    }

    public Integer getCode() {
        return code;
    }

    public Object getData() {
        return data;
    }

    public Boolean getTranslate() {
        return isTranslate;
    }
}
