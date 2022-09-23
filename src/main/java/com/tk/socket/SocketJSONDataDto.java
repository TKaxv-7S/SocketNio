package com.tk.socket;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.tk.socket.entity.Node;
import com.tk.socket.utils.JsonUtil;

import java.io.Serializable;

public class SocketJSONDataDto implements Serializable {

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
    private Node<Object> nodeData = new Node<>();

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public Integer getClientDataId() {
        return clientDataId;
    }

    public void setClientDataId(Integer clientDataId) {
        this.clientDataId = clientDataId;
    }

    public Integer getServerDataId() {
        return serverDataId;
    }

    public void setServerDataId(Integer serverDataId) {
        this.serverDataId = serverDataId;
    }

    public Node<Object> getNodeData() {
        return nodeData;
    }

    public void setJsonData(Node<Object> nodeData) {
        this.nodeData = nodeData;
    }

    public void setData(Object data) {
        nodeData.setNode(data);
    }

    @JsonIgnore
    public Object getData() {
        return nodeData.getNode();
    }

    @JsonIgnore
    public <T> T getData(JavaType javaType) {
        Object node = nodeData.getNode();
        if (node == null) {
            return null;
        }
        return JsonUtil.parseObject(node, javaType);
    }

    @JsonIgnore
    public <T> T getData(TypeReference<T> valueTypeRef) {
        Object node = nodeData.getNode();
        if (node == null) {
            return null;
        }
        return JsonUtil.parseObject(node, valueTypeRef);
    }

    @JsonIgnore
    public <T> T getData(Class<T> clazz) {
        Object node = nodeData.getNode();
        if (node == null) {
            return null;
        }
        return JsonUtil.parseObject(node, clazz);
    }

    public SocketJSONDataDto() {
    }

    public SocketJSONDataDto(String method) {
        this.method = method;
    }

    public SocketJSONDataDto(Integer dataId, boolean isClient) {
        if (isClient) {
            this.clientDataId = dataId;
        } else {
            this.serverDataId = dataId;
        }
    }

    public SocketJSONDataDto(Object data) {
        nodeData.setNode(data);
    }

    public SocketJSONDataDto(Integer dataId, Object data, boolean isClient) {
        if (isClient) {
            this.clientDataId = dataId;
        } else {
            this.serverDataId = dataId;
        }
        nodeData.setNode(data);
    }

    public SocketJSONDataDto(String method, Integer dataId, boolean isClient) {
        this.method = method;
        if (isClient) {
            this.clientDataId = dataId;
        } else {
            this.serverDataId = dataId;
        }
    }

    public SocketJSONDataDto(String method, Object data) {
        this.method = method;
        nodeData.setNode(data);
    }

    public SocketJSONDataDto(String method, Integer dataId, Object data, boolean isClient) {
        this.method = method;
        if (isClient) {
            this.clientDataId = dataId;
        } else {
            this.serverDataId = dataId;
        }
        nodeData.setNode(data);
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
