package com.tk.socket;

import lombok.Data;

import java.io.Serializable;

@Data
public class SocketMsgDto<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer size;

    private T msg;

    public SocketMsgDto() {
    }

    public SocketMsgDto(Integer size, T msg) {
        this.size = size;
        this.msg = msg;
    }
}
