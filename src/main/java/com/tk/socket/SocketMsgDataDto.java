package com.tk.socket;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.io.Serializable;
import java.util.Objects;

@Data
public class SocketMsgDataDto extends SocketJSONDataDto implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final Integer SUCCESS = 200;
    private static final String SUCCESS_MSG = "操作成功";

    public static final Integer ERROR = 500;
    private static final String ERROR_MSG = "操作失败";

    /**
     * 状态码
     */
    private Integer code;

    /**
     * 返回内容
     */
    private String msg = null;

    public SocketMsgDataDto() {
        super();
    }

    public <T> SocketMsgDataDto(T data) {
        super(data);
        this.code = SUCCESS;
    }

    public <T> SocketMsgDataDto(String method, T data) {
        super(method, data);
        this.code = SUCCESS;
    }

    public SocketMsgDataDto(Integer dataId, boolean isClient) {
        super(dataId, isClient);
        this.code = SUCCESS;
    }

    public <T> SocketMsgDataDto(String method, Integer dataId, T data, boolean isClient) {
        super(method, dataId, data, isClient);
        this.code = SUCCESS;
    }

    public <T> SocketMsgDataDto(Integer dataId, T data, boolean isClient) {
        super(dataId, data, isClient);
        this.code = SUCCESS;
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMsg() {
        return this.msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    @JsonIgnore
    public Boolean isSuccess() {
        return Objects.equals(this.code, SUCCESS);
    }

    public void setSuccess() {
        this.code = SUCCESS;
        this.msg = SUCCESS_MSG;
    }

    @JsonIgnore
    public Boolean isError() {
        return !Objects.equals(this.code, SUCCESS);
    }

    public void setError() {
        this.code = ERROR;
        this.msg = ERROR_MSG;
    }

    public void setError(String msg) {
        this.code = ERROR;
        this.msg = msg;
    }

    public void setCodeAndMsg(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public static SocketMsgDataDto build(Object data) {
        return new SocketMsgDataDto(data);
    }

    public static SocketMsgDataDto build(String method, Object data) {
        return new SocketMsgDataDto(method, data);
    }

    public static <T> SocketMsgDataDto buildError(String message) {
        SocketMsgDataDto socketMsgDataDto = new SocketMsgDataDto();
        socketMsgDataDto.setError(message);
        return socketMsgDataDto;
    }

    public static <T> SocketMsgDataDto buildError() {
        SocketMsgDataDto socketMsgDataDto = new SocketMsgDataDto();
        socketMsgDataDto.setError();
        return socketMsgDataDto;
    }

    public static SocketMsgDataDto build(String method, Integer dataId, Object data, boolean isClient) {
        return new SocketMsgDataDto(method, dataId, data, isClient);
    }

    public static SocketMsgDataDto build(Integer dataId, Object data, boolean isClient) {
        return new SocketMsgDataDto(dataId, data, isClient);
    }
}
