package com.tk.socket;

import lombok.Data;

import java.io.Serializable;

@Data
public class SocketDataDto<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 调用方法
     */
    private String method;

    /**
     * 客户端数据ID，可为空
     */
    private Integer clientDataId;

    /**
     * 服务端数据ID，可为空
     */
    private Integer serverDataId;

    /**
     * 数据
     */
    private T data;

    public SocketDataDto() {
    }

    public SocketDataDto(String method) {
        this.method = method;
    }

    public SocketDataDto(Integer dataId, boolean isClient) {
        if (isClient) {
            this.clientDataId = dataId;
        } else {
            this.serverDataId = dataId;
        }
    }

    public SocketDataDto(T data) {
        this.data = data;
    }

    public SocketDataDto(Integer dataId, T data, boolean isClient) {
        if (isClient) {
            this.clientDataId = dataId;
        } else {
            this.serverDataId = dataId;
        }
        this.data = data;
    }

    public SocketDataDto(String method, Integer dataId, boolean isClient) {
        this.method = method;
        if (isClient) {
            this.clientDataId = dataId;
        } else {
            this.serverDataId = dataId;
        }
    }

    public SocketDataDto(String method, T data) {
        this.method = method;
        this.data = data;
    }

    public SocketDataDto(String method, Integer dataId, T data, boolean isClient) {
        this.method = method;
        if (isClient) {
            this.clientDataId = dataId;
        } else {
            this.serverDataId = dataId;
        }
        this.data = data;
    }

    public static <T> SocketDataDto<T> build(String method, T data) {
        return new SocketDataDto<>(method, data);
    }

    public static <T> SocketDataDto<T> buildServer(String method, Integer dataId, T data) {
        return new SocketDataDto<>(method, dataId, data, false);
    }

    public static <T> SocketDataDto<T> buildClient(String method, Integer dataId, T data) {
        return new SocketDataDto<>(method, dataId, data, true);
    }

    public static <T> SocketDataDto<T> buildServer(Integer dataId, T data) {
        return new SocketDataDto<>(dataId, data, false);
    }

    public static <T> SocketDataDto<T> buildClient(Integer dataId, T data) {
        return new SocketDataDto<>(dataId, data, true);
    }

    public static <T> SocketDataDto<T> build(String method, Integer dataId, T data, boolean isClient) {
        return new SocketDataDto<>(method, dataId, data, isClient);
    }

    public static <T> SocketDataDto<T> build(Integer dataId, T data, boolean isClient) {
        return new SocketDataDto<>(dataId, data, isClient);
    }
}
