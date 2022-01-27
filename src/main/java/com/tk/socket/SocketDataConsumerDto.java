package com.tk.socket;

import lombok.Data;

import java.io.Serializable;

@Data
public class SocketDataConsumerDto<C, D> implements Serializable {

    private static final long serialVersionUID = 1L;

    private C channel;

    private D data;

    public SocketDataConsumerDto() {
    }

    public SocketDataConsumerDto(C channel, D data) {
        this.channel = channel;
        this.data = data;
    }
}
