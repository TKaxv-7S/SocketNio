package com.tk.socket;

import lombok.Data;

import java.io.Serializable;

@Data
public class SocketEncodeDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private byte[] data;

    private byte secretByte;

    public SocketEncodeDto() {
    }

    public SocketEncodeDto(byte[] data, byte secretByte) {
        this.data = data;
        this.secretByte = secretByte;
    }

}
