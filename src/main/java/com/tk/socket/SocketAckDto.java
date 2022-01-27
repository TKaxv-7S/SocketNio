package com.tk.socket;

import lombok.Data;

import java.io.Serializable;

@Data
public class SocketAckDto<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private byte[] ackBytes;

    private T channel;

    public SocketAckDto() {
    }

    public SocketAckDto(T channel, byte[] ackBytes) {
        this.channel = channel;
        this.ackBytes = ackBytes;
    }

}
