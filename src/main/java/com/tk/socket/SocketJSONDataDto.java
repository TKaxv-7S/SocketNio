package com.tk.socket;

import cn.hutool.json.JSONObject;
import lombok.Data;

import java.io.Serializable;

@Data
public class SocketJSONDataDto implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String JSON_DATA_KEY = "data";

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
    private JSONObject jsonData;

    public void setData(Object data) {
        jsonData.set(JSON_DATA_KEY, data);
    }

    public Object getData() {
        return jsonData.get(JSON_DATA_KEY);
    }

    public <T> T getData(Class<T> tClass) {
        return jsonData.get(JSON_DATA_KEY, tClass);
    }

    public SocketJSONDataDto() {
        jsonData = new JSONObject();
    }

    public SocketJSONDataDto(String method) {
        this.method = method;
        jsonData = new JSONObject();
    }

    public SocketJSONDataDto(Integer dataId, boolean isClient) {
        if (isClient) {
            this.clientDataId = dataId;
        } else {
            this.serverDataId = dataId;
        }
        jsonData = new JSONObject();
    }

    public SocketJSONDataDto(Object data) {
        jsonData = new JSONObject();
        jsonData.set(JSON_DATA_KEY, data);
    }

    public SocketJSONDataDto(Integer dataId, Object data, boolean isClient) {
        if (isClient) {
            this.clientDataId = dataId;
        } else {
            this.serverDataId = dataId;
        }
        jsonData = new JSONObject();
        jsonData.set(JSON_DATA_KEY, data);
    }

    public SocketJSONDataDto(String method, Integer dataId, boolean isClient) {
        this.method = method;
        if (isClient) {
            this.clientDataId = dataId;
        } else {
            this.serverDataId = dataId;
        }
        jsonData = new JSONObject();
    }

    public SocketJSONDataDto(String method, Object data) {
        this.method = method;
        jsonData = new JSONObject();
        jsonData.set(JSON_DATA_KEY, data);
    }

    public SocketJSONDataDto(String method, Integer dataId, Object data, boolean isClient) {
        this.method = method;
        if (isClient) {
            this.clientDataId = dataId;
        } else {
            this.serverDataId = dataId;
        }
        jsonData = new JSONObject();
        jsonData.set(JSON_DATA_KEY, data);
    }

    public static SocketJSONDataDto build(String method, Object data) {
        return new SocketJSONDataDto(method, data);
    }

    public static SocketJSONDataDto buildServer(String method, Integer dataId, Object data) {
        return new SocketJSONDataDto(method, dataId, data, false);
    }

    public static SocketJSONDataDto buildClient(String method, Integer dataId, Object data) {
        return new SocketJSONDataDto(method, dataId, data, true);
    }

    public static SocketJSONDataDto buildServer(Integer dataId, Object data) {
        return new SocketJSONDataDto(dataId, data, false);
    }

    public static SocketJSONDataDto buildClient(Integer dataId, Object data) {
        return new SocketJSONDataDto(dataId, data, true);
    }

    public static SocketJSONDataDto build(String method, Integer dataId, Object data, boolean isClient) {
        return new SocketJSONDataDto(method, dataId, data, isClient);
    }

    public static SocketJSONDataDto build(Integer dataId, Object data, boolean isClient) {
        return new SocketJSONDataDto(dataId, data, isClient);
    }
}
